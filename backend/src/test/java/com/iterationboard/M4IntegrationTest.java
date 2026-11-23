package com.iterationboard;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M4 集成测试：AI 服务层（mock 模式）六项功能全流程、导入去重与整批撤销、适配器同步与失败通知、
 * Q1/Q3/Q7/Q9/Q11/Q13 关键断言、问答不串组。MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class M4IntegrationTest {

    @Autowired MockMvc mvc;

    // ---------- helpers（与 Revision2 测试同构） ----------

    private String captchaCode(MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/captcha").session(session))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        String captchaId = JsonPath.read(body, "$.captchaId");
        String svg = JsonPath.read(body, "$.imageSvg");
        Matcher m = Pattern.compile("<text[^>]*>(.)</text>").matcher(svg);
        StringBuilder code = new StringBuilder();
        while (m.find()) code.append(m.group(1));
        return captchaId + "|" + code;
    }

    private MockHttpSession register(String loginName, String displayName) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String[] c = captchaCode(session).split("\\|");
        mvc.perform(post("/api/v1/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"displayName\":\"" + displayName
                                + "\",\"password\":\"passw0rd1!\",\"captchaId\":\"" + c[0]
                                + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        return session;
    }

    private long myId(MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private record TeamCtx(long teamId, String invite) {}

    private TeamCtx createTeam(MockHttpSession session, String name, String code) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"joinApproval\":false}"))
                .andExpect(status().isCreated()).andReturn();
        String body = r.getResponse().getContentAsString();
        return new TeamCtx(((Number) JsonPath.read(body, "$.id")).longValue(), JsonPath.read(body, "$.inviteCode"));
    }

    private void join(MockHttpSession session, String invite) throws Exception {
        mvc.perform(post("/api/v1/teams/join").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk());
    }

    private void setRole(MockHttpSession leader, long teamId, long userId, String role) throws Exception {
        mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + userId + "/role").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isNoContent());
    }

    private record Fixture(MockHttpSession leader, long leaderId, MockHttpSession po, long poId, TeamCtx team,
                           List<MockHttpSession> members, List<Long> memberIds) {}

    private Fixture fixture(String prefix, String code, int memberCount) throws Exception {
        MockHttpSession leader = register(prefix + "lead", prefix + "组长");
        long leaderId = myId(leader);
        TeamCtx team = createTeam(leader, prefix + "组", code);
        MockHttpSession po = register(prefix + "po", prefix + "PO");
        long poId = myId(po);
        join(po, team.invite());
        setRole(leader, team.teamId(), poId, "PO");
        List<MockHttpSession> members = new ArrayList<>();
        List<Long> memberIds = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            MockHttpSession m = register(prefix + "m" + i, prefix + "成员" + i);
            memberIds.add(myId(m));
            join(m, team.invite());
            members.add(m);
        }
        return new Fixture(leader, leaderId, po, poId, team, members, memberIds);
    }

    private long createItem(MockHttpSession s, long teamId, String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(Integer.valueOf(r.getResponse().getStatus())).isEqualTo(201);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String itemJson(MockHttpSession s, long teamId, long itemId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(s))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    private int itemVersion(MockHttpSession s, long teamId, long itemId) throws Exception {
        return ((Number) JsonPath.read(itemJson(s, teamId, itemId), "$.version")).intValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(String body, String path) {
        return (List<String>) (List<?>) JsonPath.read(body, path);
    }

    private MvcResult postJson(MockHttpSession s, String url, String json) throws Exception {
        return mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    // ---------- AI 状态与小组开关 ----------

    @Test
    void aiStatusAndTeamSettings() throws Exception {
        Fixture f = fixture("st", "STA", 1);
        long teamId = f.team().teamId();
        MvcResult st = mvc.perform(get("/api/v1/teams/" + teamId + "/ai/status").session(f.members().get(0)))
                .andExpect(status().isOk()).andReturn();
        String body = st.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.available")).isTrue();
        assertThat((Boolean) JsonPath.read(body, "$.visionAvailable")).isTrue();
        assertThat((String) JsonPath.read(body, "$.mode")).isEqualTo("mock");
        assertThat((Boolean) JsonPath.read(body, "$.features.qa")).isTrue();

        // 成员改开关 → 403；组长关 qa 后问答 403 AI_FEATURE_DISABLED
        mvc.perform(put("/api/v1/teams/" + teamId + "/ai/settings").session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qa\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/teams/" + teamId + "/ai/settings").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"qa\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.qa").value(false));
        MvcResult qa = postJson(f.leader(), "/api/v1/teams/" + teamId + "/ai/qa", "{\"question\":\"有什么事项\"}");
        assertThat(Integer.valueOf(qa.getResponse().getStatus())).isEqualTo(403);
        assertThat((String) JsonPath.read(qa.getResponse().getContentAsString(), "$.code")).isEqualTo("AI_FEATURE_DISABLED");
    }

    // ---------- 粘贴导入：预览 → 确认（幂等）→ 去重更新 → 整批撤销 ----------

    @Test
    void pasteImportPreviewConfirmDedupAndUndo() throws Exception {
        Fixture f = fixture("pi", "PIA", 1);
        long teamId = f.team().teamId();
        String text = "EXT-1 门户改版确认\\nEXT-2 导出性能优化";
        // 成员解析 → 403（仅 PO）
        assertThat(Integer.valueOf(postJson(f.members().get(0),
                "/api/v1/teams/" + teamId + "/ai/paste-import", "{\"text\":\"" + text + "\"}")
                .getResponse().getStatus())).isEqualTo(403);
        MvcResult preview = postJson(f.po(), "/api/v1/teams/" + teamId + "/ai/paste-import",
                "{\"text\":\"" + text + "\"}");
        assertThat(Integer.valueOf(preview.getResponse().getStatus())).isEqualTo(200);
        String pb = preview.getResponse().getContentAsString();
        assertThat(strList(pb, "$.rows")).hasSize(2);
        assertThat((String) JsonPath.read(pb, "$.rows[0].matchStatus")).isEqualTo("NEW");
        assertThat((String) JsonPath.read(pb, "$.rows[0].externalId")).isEqualTo("EXT-1");

        String confirm = "{\"source\":\"AI_PASTE\",\"rows\":["
                + "{\"externalId\":\"EXT-1\",\"title\":\"门户改版确认\"},"
                + "{\"externalId\":\"EXT-2\",\"title\":\"导出性能优化\"}]}";
        // 同幂等键两次 → 只生成一个批次
        MvcResult c1 = mvc.perform(post("/api/v1/teams/" + teamId + "/imports").session(f.po()).with(csrf())
                        .header("Idempotency-Key", "pi-key-1").contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isCreated()).andReturn();
        long batch1 = ((Number) JsonPath.read(c1.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult c2 = mvc.perform(post("/api/v1/teams/" + teamId + "/imports").session(f.po()).with(csrf())
                        .header("Idempotency-Key", "pi-key-1").contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isCreated()).andReturn();
        assertThat(((Number) JsonPath.read(c2.getResponse().getContentAsString(), "$.id")).longValue()).isEqualTo(batch1);
        MvcResult tasks = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.po()).param("type", "TASK"))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(tasks.getResponse().getContentAsString(), "$[*].id")).hasSize(2);

        // 同编号再导入（改标题）→ 只更新外部字段，生成新批次
        String confirm2 = "{\"source\":\"AI_PASTE\",\"rows\":[{\"externalId\":\"EXT-1\",\"title\":\"门户改版确认 v2\"}]}";
        MvcResult c3 = postJson(f.po(), "/api/v1/teams/" + teamId + "/imports", confirm2);
        String c3b = c3.getResponse().getContentAsString();
        long batch2 = ((Number) JsonPath.read(c3b, "$.id")).longValue();
        assertThat(((Number) JsonPath.read(c3b, "$.createdCount")).intValue()).isEqualTo(0);
        assertThat(((Number) JsonPath.read(c3b, "$.updatedCount")).intValue()).isEqualTo(1);
        MvcResult tasks2 = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.po()).param("type", "TASK"))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(tasks2.getResponse().getContentAsString(), "$[*].title"))
                .contains("门户改版确认 v2");

        // 撤销批次2 → 标题回滚；撤销批次1 → 任务进回收站
        postJson(f.po(), "/api/v1/teams/" + teamId + "/imports/" + batch2 + "/undo", "{}");
        MvcResult tasks3 = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.po()).param("type", "TASK"))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(tasks3.getResponse().getContentAsString(), "$[*].title"))
                .contains("门户改版确认");
        postJson(f.leader(), "/api/v1/teams/" + teamId + "/imports/" + batch1 + "/undo", "{}");
        MvcResult tasks4 = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.po()).param("type", "TASK"))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(tasks4.getResponse().getContentAsString(), "$[*].id")).isEmpty();
    }

    @Test
    void importRowLimitAndFileMapping() throws Exception {
        Fixture f = fixture("fm", "FMA", 0);
        long teamId = f.team().teamId();
        // CSV 解析 → 列映射确认（不依赖 AI）
        String csv = "编号,标题,优先级\nF-1,文件导入任务一,P1\nF-2,文件导入任务二,P2\n";
        MockMultipartFile file = new MockMultipartFile("file", "tasks.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MvcResult parsed = mvc.perform(multipart("/api/v1/teams/" + teamId + "/imports/file/parse").file(file)
                        .session(f.po()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String pb = parsed.getResponse().getContentAsString();
        assertThat(strList(pb, "$.headers")).hasSize(3);
        assertThat(strList(pb, "$.rows")).hasSize(2);
        String confirm = "{\"source\":\"FILE_MAPPING\",\"mapping\":{\"externalId\":0,\"title\":1,\"priority\":2},"
                + "\"rawRows\":[[\"F-1\",\"文件导入任务一\",\"P1\"],[\"F-2\",\"文件导入任务二\",\"P2\"]]}";
        MvcResult c = postJson(f.po(), "/api/v1/teams/" + teamId + "/imports", confirm);
        assertThat(Integer.valueOf(c.getResponse().getStatus())).isEqualTo(201);
        assertThat(((Number) JsonPath.read(c.getResponse().getContentAsString(), "$.createdCount")).intValue()).isEqualTo(2);
    }

    @Test
    void importCreatesItemWithEstimateAndUpdateKeepsEstimate() throws Exception {
        Fixture f = fixture("ie", "IEA", 0);
        long teamId = f.team().teamId();
        // 契约 0.5.1：ImportRowInput.estimate 在新建事项时落库
        String confirm = "{\"source\":\"AI_PASTE\",\"rows\":[{\"externalId\":\"EST-1\",\"title\":\"带估算的导入任务\",\"estimate\":5.5}]}";
        MvcResult c = postJson(f.po(), "/api/v1/teams/" + teamId + "/imports", confirm);
        assertThat(Integer.valueOf(c.getResponse().getStatus())).isEqualTo(201);
        assertThat(((Number) JsonPath.read(c.getResponse().getContentAsString(), "$.createdCount")).intValue()).isEqualTo(1);
        MvcResult tasks = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.po()).param("type", "TASK"))
                .andExpect(status().isOk()).andReturn();
        String tb = tasks.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(tb, "$[0].estimate")).doubleValue()).isEqualTo(5.5);
        // 更新既有事项（同外部编号）只覆盖外部字段，不覆盖 estimate
        String confirm2 = "{\"source\":\"AI_PASTE\",\"rows\":[{\"externalId\":\"EST-1\",\"title\":\"带估算的导入任务 v2\",\"estimate\":9}]}";
        MvcResult c2 = postJson(f.po(), "/api/v1/teams/" + teamId + "/imports", confirm2);
        assertThat(((Number) JsonPath.read(c2.getResponse().getContentAsString(), "$.updatedCount")).intValue()).isEqualTo(1);
        MvcResult tasks2 = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.po()).param("type", "TASK"))
                .andExpect(status().isOk()).andReturn();
        String tb2 = tasks2.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(tb2, "$[0].title")).isEqualTo("带估算的导入任务 v2");
        assertThat(((Number) JsonPath.read(tb2, "$[0].estimate")).doubleValue()).isEqualTo(5.5);
    }

    // ---------- 一句话更新 ----------

    @Test
    void quickUpdateParseAndApplyIdempotent() throws Exception {
        Fixture f = fixture("qu", "QUA", 1);
        long teamId = f.team().teamId();
        long memberId = f.memberIds().get(0);
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"DEFECT\",\"title\":\"联调缺陷修复\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + memberId + "]}");
        MvcResult parsed = postJson(f.members().get(0), "/api/v1/teams/" + teamId + "/ai/quick-update",
                "{\"text\":\"联调做了一半 50%，等测试环境\"}");
        assertThat(Integer.valueOf(parsed.getResponse().getStatus())).isEqualTo(200);
        String pb = parsed.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(pb, "$.actions[0].kind")).isEqualTo("UPDATE_ITEM");
        assertThat(((Number) JsonPath.read(pb, "$.actions[0].itemId")).longValue()).isEqualTo(itemId);
        assertThat(((Number) JsonPath.read(pb, "$.actions[0].percent")).intValue()).isEqualTo(50);
        assertThat((Boolean) JsonPath.read(pb, "$.actions[0].needsBlockConfirm")).isTrue();

        String apply = "{\"actions\":[{\"kind\":\"UPDATE_ITEM\",\"itemId\":" + itemId
                + ",\"text\":\"联调做了一半\",\"percent\":50}]}";
        mvc.perform(post("/api/v1/teams/" + teamId + "/ai/quick-update/apply").session(f.members().get(0)).with(csrf())
                        .header("Idempotency-Key", "qu-key-1").contentType(MediaType.APPLICATION_JSON).content(apply))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/teams/" + teamId + "/ai/quick-update/apply").session(f.members().get(0)).with(csrf())
                        .header("Idempotency-Key", "qu-key-1").contentType(MediaType.APPLICATION_JSON).content(apply))
                .andExpect(status().isOk());
        MvcResult progress = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId + "/progress-list")
                        .session(f.members().get(0)))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(progress.getResponse().getContentAsString(), "$[*].id")).hasSize(1);
        assertThat(((Number) JsonPath.read(itemJson(f.members().get(0), teamId, itemId), "$.progressPercent")).intValue()).isEqualTo(50);
    }

    // ---------- AI 拆故事（1.1.0 修订四 P06：组长和 PO 都可，原「仅 PO」断言随规则变更调整；无建议负责人不变） ----------

    @Test
    void splitStoriesLeaderAndPoNoOwnerSuggestion() throws Exception {
        Fixture f = fixture("ss", "SSA", 1);
        long teamId = f.team().teamId();
        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"支付对账功能\"}");
        assertThat(Integer.valueOf(postJson(f.members().get(0),
                "/api/v1/teams/" + teamId + "/ai/split-stories", "{\"itemId\":" + taskId + "}")
                .getResponse().getStatus())).isEqualTo(403);
        assertThat(Integer.valueOf(postJson(f.leader(),
                "/api/v1/teams/" + teamId + "/ai/split-stories", "{\"itemId\":" + taskId + "}")
                .getResponse().getStatus())).isEqualTo(200);
        MvcResult r = postJson(f.po(), "/api/v1/teams/" + teamId + "/ai/split-stories", "{\"itemId\":" + taskId + "}");
        assertThat(Integer.valueOf(r.getResponse().getStatus())).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        assertThat(strList(body, "$.stories")).hasSize(2);
        assertThat(body).doesNotContain("owner").doesNotContain("assignee");
        assertThat(strList(body, "$.clarifyingQuestions")).isNotEmpty();
    }

    // ---------- 自动汇总：summarize 预览 + 报告生成走 AI 叙述 ----------

    @Test
    void summarizePreviewAndReportGenerationUseAiNarrative() throws Exception {
        Fixture f = fixture("sm", "SMA", 1);
        long teamId = f.team().teamId();
        createItem(f.leader(), teamId, "{\"type\":\"DEFECT\",\"title\":\"汇总样本缺陷\",\"destination\":\"POOL\"}");
        MvcResult prev = postJson(f.leader(), "/api/v1/teams/" + teamId + "/ai/summarize",
                "{\"kind\":\"WEEKLY\"}");
        assertThat(Integer.valueOf(prev.getResponse().getStatus())).isEqualTo(200);
        String pb = prev.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(pb, "$.aiGenerated")).isTrue();
        assertThat((String) JsonPath.read(pb, "$.content")).contains("本期新建");

        MvcResult gen = postJson(f.leader(), "/api/v1/teams/" + teamId + "/reports/generate",
                "{\"kind\":\"WEEKLY\"}");
        assertThat(Integer.valueOf(gen.getResponse().getStatus())).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(gen.getResponse().getContentAsString(), "$.aiGenerated")).isTrue();
    }

    // ---------- 风险摘要 ----------

    @Test
    void riskSummaryTopAndSignals() throws Exception {
        Fixture f = fixture("rk", "RKA", 1);
        long teamId = f.team().teamId();
        long memberId = f.memberIds().get(0);
        createItem(f.leader(), teamId,
                "{\"type\":\"DEFECT\",\"title\":\"逾期缺陷\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + memberId
                        + "],\"dueDate\":\"" + LocalDate.now().minusDays(1) + "\"}");
        assertThat(Integer.valueOf(mvc.perform(get("/api/v1/teams/" + teamId + "/ai/risk-summary")
                .session(f.members().get(0))).andReturn().getResponse().getStatus())).isEqualTo(403);
        MvcResult r = mvc.perform(get("/api/v1/teams/" + teamId + "/ai/risk-summary").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.aiGenerated")).isTrue();
        assertThat(strList(body, "$.signals")).isNotEmpty();
        assertThat(strList(body, "$.top")).isNotEmpty();
        assertThat((String) JsonPath.read(body, "$.top[0].itemCode")).startsWith("RKA-");
    }

    // ---------- 问答：不串组 + 历史仅本人 ----------

    @Test
    void qaDoesNotLeakAcrossTeamsAndHistoryIsPrivate() throws Exception {
        Fixture f = fixture("qa", "QAA", 1);
        long teamId = f.team().teamId();
        long itemId = createItem(f.leader(), teamId, "{\"type\":\"DEFECT\",\"title\":\"问答样本缺陷\",\"destination\":\"POOL\"}");
        String code = JsonPath.read(itemJson(f.leader(), teamId, itemId), "$.code");
        MvcResult r = postJson(f.leader(), "/api/v1/teams/" + teamId + "/ai/qa",
                "{\"question\":\"" + code + " 现在到哪一步了\"}");
        assertThat(Integer.valueOf(r.getResponse().getStatus())).isEqualTo(200);
        String body = r.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.found")).isTrue();
        assertThat(strList(body, "$.refs")).contains(code);

        // 另一个小组问同一编号 → 查不到
        MockHttpSession other = register("qaother", "外组组长");
        TeamCtx team2 = createTeam(other, "外组", "QAB");
        MvcResult r2 = postJson(other, "/api/v1/teams/" + team2.teamId() + "/ai/qa",
                "{\"question\":\"" + code + " 现在到哪一步了\"}");
        assertThat((Boolean) JsonPath.read(r2.getResponse().getContentAsString(), "$.found")).isFalse();

        // 历史仅本人：成员看不到组长的记录；组长可删自己的
        MvcResult histMember = mvc.perform(get("/api/v1/teams/" + teamId + "/ai/qa/history").session(f.members().get(0)))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(histMember.getResponse().getContentAsString(), "$[*].id")).isEmpty();
        MvcResult hist = mvc.perform(get("/api/v1/teams/" + teamId + "/ai/qa/history").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        long recordId = ((Number) JsonPath.read(hist.getResponse().getContentAsString(), "$[0].id")).longValue();
        mvc.perform(delete("/api/v1/teams/" + teamId + "/ai/qa/history/" + recordId).session(f.members().get(0)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/teams/" + teamId + "/ai/qa/history/" + recordId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---------- AI 调用日志 ----------

    @Test
    void aiCallLogRecorded() throws Exception {
        Fixture f = fixture("lg", "LGA", 0);
        long teamId = f.team().teamId();
        postJson(f.po(), "/api/v1/teams/" + teamId + "/ai/paste-import", "{\"text\":\"EXT-9 日志样本\"}");
        assertThat(Integer.valueOf(mvc.perform(get("/api/v1/teams/" + teamId + "/ai/logs")
                .session(f.po())).andReturn().getResponse().getStatus())).isEqualTo(403);
        MvcResult logs = mvc.perform(get("/api/v1/teams/" + teamId + "/ai/logs").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(logs.getResponse().getContentAsString(), "$[*].feature"))
                .contains("paste-import");
    }

    // ---------- 对接：MOCK 同步 + HTTP 连续失败 3 次通知组长 ----------

    @Test
    void adapterMockSyncAndHttpFailureNotification() throws Exception {
        Fixture f = fixture("ad", "ADA", 1);
        long teamId = f.team().teamId();
        // 非组长不能配置
        assertThat(Integer.valueOf(postJson(f.po(), "/api/v1/teams/" + teamId + "/integrations/adapters",
                "{\"name\":\"x\",\"type\":\"MOCK\",\"systemName\":\"MOCKSYS\",\"config\":{}}")
                .getResponse().getStatus())).isEqualTo(403);
        MvcResult created = postJson(f.leader(), "/api/v1/teams/" + teamId + "/integrations/adapters",
                "{\"name\":\"模拟源\",\"type\":\"MOCK\",\"systemName\":\"MOCKSYS\",\"config\":{}}");
        assertThat(Integer.valueOf(created.getResponse().getStatus())).isEqualTo(201);
        long adapterId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult test = postJson(f.leader(), "/api/v1/teams/" + teamId + "/integrations/adapters/" + adapterId + "/test", "{}");
        assertThat((Boolean) JsonPath.read(test.getResponse().getContentAsString(), "$.ok")).isTrue();
        assertThat(((Number) JsonPath.read(test.getResponse().getContentAsString(), "$.count")).intValue()).isEqualTo(3);
        MvcResult sync1 = postJson(f.leader(), "/api/v1/teams/" + teamId + "/integrations/adapters/" + adapterId + "/sync", "{}");
        assertThat(((Number) JsonPath.read(sync1.getResponse().getContentAsString(), "$.createdCount")).intValue()).isEqualTo(3);
        MvcResult sync2 = postJson(f.leader(), "/api/v1/teams/" + teamId + "/integrations/adapters/" + adapterId + "/sync", "{}");
        assertThat(((Number) JsonPath.read(sync2.getResponse().getContentAsString(), "$.createdCount")).intValue()).isEqualTo(0);
        assertThat(((Number) JsonPath.read(sync2.getResponse().getContentAsString(), "$.updatedCount")).intValue()).isEqualTo(3);
        // 外部已关闭只加标记不改状态
        MvcResult tasks = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.leader()).param("type", "TASK"))
                .andExpect(status().isOk()).andReturn();
        String tb = tasks.getResponse().getContentAsString();
        assertThat(strList(tb, "$[*].externalId")).contains("MOCK-101", "MOCK-103");
        assertThat(tb).contains("\"externalClosed\":true");

        // HTTP 适配器指向不可达地址：由第二组长触发同步连续失败 3 次 → 第一组长收到 SYNC_FAILED 通知
        // （触发者本人也应收到——yefqkvf3 待问 1 的含本人断言见 syncFailedNotifiesTriggeringLeaderToo）
        setRole(f.leader(), teamId, f.memberIds().get(0), "LEADER");
        MvcResult http = postJson(f.leader(), "/api/v1/teams/" + teamId + "/integrations/adapters",
                "{\"name\":\"坏源\",\"type\":\"HTTP\",\"systemName\":\"BADSYS\",\"config\":{\"url\":\"http://127.0.0.1:1/ext\",\"listPath\":\"$\"}}");
        long badId = ((Number) JsonPath.read(http.getResponse().getContentAsString(), "$.id")).longValue();
        for (int i = 0; i < 3; i++) {
            MvcResult s = postJson(f.members().get(0), "/api/v1/teams/" + teamId + "/integrations/adapters/" + badId + "/sync", "{}");
            assertThat((Boolean) JsonPath.read(s.getResponse().getContentAsString(), "$.success")).isFalse();
        }
        MvcResult adapters = mvc.perform(get("/api/v1/teams/" + teamId + "/integrations/adapters").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat(adapters.getResponse().getContentAsString()).contains("\"consecutiveFailures\":3");
        MvcResult notif = mvc.perform(get("/api/v1/notifications").session(f.leader()).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        assertThat(notif.getResponse().getContentAsString()).contains("\"type\":\"SYNC_FAILED\"");
    }

    // ---------- yefqkvf3 待问 1：同步连败通知所有组长（含触发者本人）；其他类型仍不通知自己 ----------

    @Test
    void syncFailedNotifiesTriggeringLeaderToo() throws Exception {
        Fixture f = fixture("sf", "SFA", 1);
        long teamId = f.team().teamId();
        // 第二位组长 B（由成员 0 升任）；组长 A = f.leader()
        setRole(f.leader(), teamId, f.memberIds().get(0), "LEADER");
        MvcResult http = postJson(f.leader(), "/api/v1/teams/" + teamId + "/integrations/adapters",
                "{\"name\":\"坏源甲\",\"type\":\"HTTP\",\"systemName\":\"BADONE\",\"config\":{\"url\":\"http://127.0.0.1:1/ext\",\"listPath\":\"$\"}}");
        long badId = ((Number) JsonPath.read(http.getResponse().getContentAsString(), "$.id")).longValue();
        // 组长 A 本人亲手触发连续 3 次同步失败
        for (int i = 0; i < 3; i++) {
            MvcResult s = postJson(f.leader(), "/api/v1/teams/" + teamId + "/integrations/adapters/" + badId + "/sync", "{}");
            assertThat((Boolean) JsonPath.read(s.getResponse().getContentAsString(), "$.success")).isFalse();
        }
        // ① A 本人收到 SYNC_FAILED（旧行为会被「不通知自己」挡掉）
        MvcResult notifA = mvc.perform(get("/api/v1/notifications").session(f.leader()).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        assertThat(notifA.getResponse().getContentAsString()).contains("\"type\":\"SYNC_FAILED\"");
        // ① B 也收到 SYNC_FAILED
        MvcResult notifB = mvc.perform(get("/api/v1/notifications").session(f.members().get(0)).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        assertThat(notifB.getResponse().getContentAsString()).contains("\"type\":\"SYNC_FAILED\"");

        // ② 回归：其他类型通知仍不发给自己——A 开始迭代，A 不收 ITERATION_STARTED，B 收得到
        MvcResult it = postJson(f.leader(), "/api/v1/teams/" + teamId + "/iterations",
                "{\"name\":\"回归迭代\",\"startDate\":\"" + LocalDate.now() + "\",\"endDate\":\"" + LocalDate.now().plusDays(13) + "\"}");
        long itId = ((Number) JsonPath.read(it.getResponse().getContentAsString(), "$.id")).longValue();
        postJson(f.leader(), "/api/v1/teams/" + teamId + "/iterations/" + itId + "/start", "{}");
        MvcResult afterA = mvc.perform(get("/api/v1/notifications").session(f.leader()).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        assertThat(afterA.getResponse().getContentAsString()).doesNotContain("\"type\":\"ITERATION_STARTED\"");
        MvcResult afterB = mvc.perform(get("/api/v1/notifications").session(f.members().get(0)).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        assertThat(afterB.getResponse().getContentAsString()).contains("\"type\":\"ITERATION_STARTED\"");
    }

    // ---------- Q3：迭代写操作仅组长；规划增减组长+PO ----------

    @Test
    void q3IterationWriteLeaderOnlyPlanningLeaderOrPo() throws Exception {
        Fixture f = fixture("q3", "QTA", 1);
        long teamId = f.team().teamId();
        // PO 创建迭代 → 403
        assertThat(Integer.valueOf(postJson(f.po(), "/api/v1/teams/" + teamId + "/iterations",
                "{\"name\":\"PO的迭代\",\"startDate\":\"" + LocalDate.now() + "\",\"endDate\":\"" + LocalDate.now().plusDays(13) + "\"}")
                .getResponse().getStatus())).isEqualTo(403);
        MvcResult it = postJson(f.leader(), "/api/v1/teams/" + teamId + "/iterations",
                "{\"name\":\"迭代一\",\"startDate\":\"" + LocalDate.now() + "\",\"endDate\":\"" + LocalDate.now().plusDays(13) + "\"}");
        long itId = ((Number) JsonPath.read(it.getResponse().getContentAsString(), "$.id")).longValue();
        // PO 改属性/开始 → 403
        assertThat(Integer.valueOf(mvc.perform(patch("/api/v1/teams/" + teamId + "/iterations/" + itId)
                        .session(f.po()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"改名\"}"))
                .andReturn().getResponse().getStatus())).isEqualTo(403);
        assertThat(Integer.valueOf(postJson(f.po(), "/api/v1/teams/" + teamId + "/iterations/" + itId + "/start", "{}")
                .getResponse().getStatus())).isEqualTo(403);
        // 规划增减：成员 PATCH 事项 iterationId → 403；PO → 200
        long itemId = createItem(f.leader(), teamId, "{\"type\":\"DEFECT\",\"title\":\"规划样本\",\"destination\":\"POOL\"}");
        int v1 = itemVersion(f.members().get(0), teamId, itemId);
        assertThat(Integer.valueOf(mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId)
                        .session(f.members().get(0)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v1 + ",\"iterationId\":" + itId + "}"))
                .andReturn().getResponse().getStatus())).isEqualTo(403);
        int v2 = itemVersion(f.po(), teamId, itemId);
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId)
                        .session(f.po()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v2 + ",\"iterationId\":" + itId + "}"))
                .andExpect(status().isOk());
        // 删除：PO → 403；组长删（先移出事项）——事项在迭代中时组长删 → 409；移出后可删
        assertThat(Integer.valueOf(mvc.perform(delete("/api/v1/teams/" + teamId + "/iterations/" + itId)
                        .session(f.po()).with(csrf())).andReturn().getResponse().getStatus())).isEqualTo(403);
        assertThat(Integer.valueOf(mvc.perform(delete("/api/v1/teams/" + teamId + "/iterations/" + itId)
                        .session(f.leader()).with(csrf())).andReturn().getResponse().getStatus())).isEqualTo(409);
        int v3 = itemVersion(f.po(), teamId, itemId);
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId)
                        .session(f.po()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v3 + ",\"iterationId\":null}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/teams/" + teamId + "/iterations/" + itId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---------- Q7：迭代总结按 iterationId 唯一 ----------

    @Test
    void q7IterationSummaryUniqueByIterationId() throws Exception {
        Fixture f = fixture("q7", "QSA", 0);
        long teamId = f.team().teamId();
        MvcResult it = postJson(f.leader(), "/api/v1/teams/" + teamId + "/iterations",
                "{\"name\":\"总结迭代\",\"startDate\":\"" + LocalDate.now().minusDays(13) + "\",\"endDate\":\"" + LocalDate.now() + "\"}");
        long itId = ((Number) JsonPath.read(it.getResponse().getContentAsString(), "$.id")).longValue();
        postJson(f.leader(), "/api/v1/teams/" + teamId + "/iterations/" + itId + "/start", "{}");
        MvcResult closed = postJson(f.leader(), "/api/v1/teams/" + teamId + "/iterations/" + itId + "/close",
                "{\"decisions\":[],\"improvements\":[]}");
        assertThat(Integer.valueOf(closed.getResponse().getStatus())).isEqualTo(200);
        long reportId = ((Number) JsonPath.read(closed.getResponse().getContentAsString(), "$.reportId")).longValue();
        // 手动按 iterationId 再生成 → 返回同一份 existed=true
        MvcResult gen = postJson(f.leader(), "/api/v1/teams/" + teamId + "/reports/generate",
                "{\"kind\":\"ITERATION_SUMMARY\",\"iterationId\":" + itId + "}");
        String gb = gen.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(gb, "$.existed")).isTrue();
        assertThat(((Number) JsonPath.read(gb, "$.id")).longValue()).isEqualTo(reportId);
        assertThat(((Number) JsonPath.read(gb, "$.iterationId")).longValue()).isEqualTo(itId);
    }

    // ---------- Q9：统一回收站 type 筛选 ----------

    @Test
    void q9UnifiedTrashTypeFilter() throws Exception {
        Fixture f = fixture("q9", "QNA", 0);
        long teamId = f.team().teamId();
        long itemId = createItem(f.leader(), teamId, "{\"type\":\"ADHOC\",\"title\":\"回收站事项\",\"destination\":\"POOL\"}");
        mvc.perform(delete("/api/v1/teams/" + teamId + "/items/" + itemId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());
        MvcResult gen = postJson(f.leader(), "/api/v1/teams/" + teamId + "/reports/generate",
                "{\"kind\":\"WEEKLY\",\"periodStart\":\"" + LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY)
                        + "\",\"periodEnd\":\"" + LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.SUNDAY) + "\"}");
        long reportId = ((Number) JsonPath.read(gen.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(delete("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());

        MvcResult all = mvc.perform(get("/api/v1/teams/" + teamId + "/trash").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(all.getResponse().getContentAsString(), "$[*].kind"))
                .containsExactlyInAnyOrder("ITEM", "REPORT");
        MvcResult itemsOnly = mvc.perform(get("/api/v1/teams/" + teamId + "/trash").session(f.leader()).param("type", "ITEM"))
                .andExpect(status().isOk()).andReturn();
        String ib = itemsOnly.getResponse().getContentAsString();
        assertThat(strList(ib, "$[*].kind")).containsExactly("ITEM");
        assertThat((String) JsonPath.read(ib, "$[0].title")).isEqualTo("回收站事项");
        assertThat((String) JsonPath.read(ib, "$[0].deletedByName")).isNotBlank();
        MvcResult reportsOnly = mvc.perform(get("/api/v1/teams/" + teamId + "/trash").session(f.leader()).param("type", "REPORT"))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(reportsOnly.getResponse().getContentAsString(), "$[*].kind"))
                .containsExactly("REPORT");
    }

    // ---------- Q11：allowedActions ----------

    @Test
    void q11AllowedActionsOnItemAndReport() throws Exception {
        Fixture f = fixture("q11", "QOA", 1);
        long teamId = f.team().teamId();
        long itemId = createItem(f.leader(), teamId, "{\"type\":\"DEFECT\",\"title\":\"动作样本\",\"destination\":\"POOL\"}");
        String memberView = itemJson(f.members().get(0), teamId, itemId);
        assertThat(strList(memberView, "$.allowedActions")).contains("CLAIM");
        MvcResult gen = postJson(f.leader(), "/api/v1/teams/" + teamId + "/reports/generate", "{\"kind\":\"WEEKLY\"}");
        long reportId = ((Number) JsonPath.read(gen.getResponse().getContentAsString(), "$.id")).longValue();
        MvcResult asMember = mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.members().get(0)))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(asMember.getResponse().getContentAsString(), "$.allowedActions"))
                .contains("VIEW").doesNotContain("EDIT", "DELETE", "REGENERATE");
        MvcResult asLeader = mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat(strList(asLeader.getResponse().getContentAsString(), "$.allowedActions"))
                .contains("VIEW", "EDIT", "DELETE", "REGENERATE", "EXPORT");
    }

    // ---------- Q13：认领/催/加入幂等键 + 重复调用安全 ----------

    @Test
    void q13IdempotencyKeysAndRepeatSafety() throws Exception {
        Fixture f = fixture("q13", "QTB", 2);
        long teamId = f.team().teamId();
        long itemId = createItem(f.leader(), teamId, "{\"type\":\"DEFECT\",\"title\":\"幂等样本\",\"destination\":\"POOL\"}");
        int v = itemVersion(f.members().get(0), teamId, itemId);
        String claim = "{\"action\":\"CLAIM\",\"version\":" + v + "}";
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/transitions").session(f.members().get(0)).with(csrf())
                        .header("Idempotency-Key", "claim-1").contentType(MediaType.APPLICATION_JSON).content(claim))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_STARTED"));
        // 同键重放（version 已变）→ 仍 200 返回首次结果
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/transitions").session(f.members().get(0)).with(csrf())
                        .header("Idempotency-Key", "claim-1").contentType(MediaType.APPLICATION_JSON).content(claim))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NOT_STARTED"));
        // 无键、旧 version 再认领（本人已是负责人）→ 返回当前状态而非报错
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/transitions").session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(claim))
                .andExpect(status().isOk());
        // 另一成员认领已被认领的事项 → 409 已被认领
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/transitions").session(f.members().get(1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(claim))
                .andExpect(status().isConflict());
        // 催一下同键两次 → 只产生一条催（nudgeCount=1）
        String nudgeUrl = "/api/v1/teams/" + teamId + "/items/" + itemId + "/nudge";
        mvc.perform(post(nudgeUrl).session(f.leader()).with(csrf()).header("Idempotency-Key", "nudge-1"))
                .andExpect(status().isOk());
        mvc.perform(post(nudgeUrl).session(f.leader()).with(csrf()).header("Idempotency-Key", "nudge-1"))
                .andExpect(status().isOk());
        assertThat(((Number) JsonPath.read(itemJson(f.leader(), teamId, itemId), "$.nudgeCount")).intValue()).isEqualTo(1);
        // 加入共同负责同键两次 → 负责人只多一人
        int vj = itemVersion(f.members().get(1), teamId, itemId);
        String joinBody = "{\"version\":" + vj + "}";
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/join").session(f.members().get(1)).with(csrf())
                        .header("Idempotency-Key", "join-1").contentType(MediaType.APPLICATION_JSON).content(joinBody))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/join").session(f.members().get(1)).with(csrf())
                        .header("Idempotency-Key", "join-1").contentType(MediaType.APPLICATION_JSON).content(joinBody))
                .andExpect(status().isOk());
        assertThat(strList(itemJson(f.leader(), teamId, itemId), "$.ownerIds")).hasSize(2);
    }

    // ---------- 新 POST 端点的真实 CSRF 令牌流（不用 csrf() 后处理器） ----------

    @Autowired
    org.springframework.security.web.FilterChainProxy filterChainProxy;

    /** 把共享 CsrfFilter 的仓库/处理器恢复为 SecurityConfig 的生产配置（见 CSRF 流测试注释）。 */
    private void restoreProductionCsrfConfig() throws Exception {
        for (org.springframework.security.web.SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
            for (jakarta.servlet.Filter f : chain.getFilters()) {
                if (f instanceof org.springframework.security.web.csrf.CsrfFilter csrfFilter) {
                    java.lang.reflect.Field repoField =
                            org.springframework.security.web.csrf.CsrfFilter.class.getDeclaredField("tokenRepository");
                    repoField.setAccessible(true);
                    repoField.set(csrfFilter,
                            org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse());
                    csrfFilter.setRequestHandler(
                            new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler());
                }
            }
        }
    }

    @Test
    void newPostEndpointsWorkWithBrowserCsrfFlow() throws Exception {
        // 令牌流说明（真实浏览器流，不用 csrf() 后处理器）：先把共享 CsrfFilter 恢复为生产配置再走 Cookie 流。
        // 原因：spring-security-test 的 csrf() 后处理器会把共享 CsrfFilter 的 tokenRepository 永久替换为
        // TestCsrfTokenRepository（WebTestUtils.setCsrfTokenRepository），任何测试类用过 csrf() 后，
        // 同 JVM 后续类再也拿不到 XSRF-TOKEN Cookie、校验也改走 X-CSRF-TOKEN——这是既有测试基础设施的
        // 共享状态污染（CsrfCookieFlowTest 靠类名排序最先运行才未受影响）。此处按 SecurityConfig 原样恢复
        // CookieCsrfTokenRepository + plain CsrfTokenRequestAttributeHandler，验证的是生产配置本身。
        restoreProductionCsrfConfig();
        MvcResult boot = mvc.perform(get("/api/v1/auth/captcha")).andReturn();
        Cookie xsrf = boot.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        MockHttpSession session = new MockHttpSession();
        String[] c = captchaCode(session).split("\\|");
        mvc.perform(post("/api/v1/auth/register").session(session).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"m4csrf\",\"displayName\":\"M4CSRF\",\"password\":\"passw0rd1!\",\"captchaId\":\""
                                + c[0] + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        MvcResult team = mvc.perform(post("/api/v1/teams").session(session).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CSRF组\",\"code\":\"CSR\",\"joinApproval\":false}"))
                .andExpect(status().isCreated()).andReturn();
        long teamId = ((Number) JsonPath.read(team.getResponse().getContentAsString(), "$.id")).longValue();
        // 新端点 1：建适配器（写端点）
        mvc.perform(post("/api/v1/teams/" + teamId + "/integrations/adapters").session(session).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"模拟源\",\"type\":\"MOCK\",\"systemName\":\"CSRFMOCK\",\"config\":{}}"))
                .andExpect(status().isCreated());
        // 新端点 2：AI 一句话更新（写路径入口）
        mvc.perform(post("/api/v1/teams/" + teamId + "/ai/quick-update").session(session).cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"今天推进了对账联调\"}"))
                .andExpect(status().isOk());
        // 缺令牌头 → 403
        mvc.perform(post("/api/v1/teams/" + teamId + "/integrations/adapters").session(session).cookie(xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"模拟源2\",\"type\":\"MOCK\",\"systemName\":\"CSRFMOCK2\",\"config\":{}}"))
                .andExpect(status().isForbidden());
    }
}
