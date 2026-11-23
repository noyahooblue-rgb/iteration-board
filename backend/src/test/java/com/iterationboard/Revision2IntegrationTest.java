package com.iterationboard;

import com.iterationboard.domain.Report;
import com.iterationboard.repo.ReportRepo;
import com.iterationboard.report.ReportService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 修订二集成测试。
 * MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Revision2IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ReportRepo reportRepo;
    @Autowired ReportService reportService;
    @Autowired jakarta.persistence.EntityManager em;

    // ---------- helpers（与 M3 测试同构） ----------

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

    private TeamCtx createTeam(MockHttpSession session, String name, String code, boolean approval) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"joinApproval\":" + approval + "}"))
                .andExpect(status().isCreated()).andReturn();
        String body = r.getResponse().getContentAsString();
        return new TeamCtx(((Number) JsonPath.read(body, "$.id")).longValue(), JsonPath.read(body, "$.inviteCode"));
    }

    private void join(MockHttpSession session, String invite) throws Exception {
        mvc.perform(post("/api/v1/teams/join").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(true));
    }

    private void setRole(MockHttpSession leader, long teamId, long userId, String role) throws Exception {
        mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + userId + "/role").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isNoContent());
    }

    private record Fixture(MockHttpSession leader, long leaderId, MockHttpSession po, long poId, TeamCtx team,
                           List<MockHttpSession> members, List<Long> memberIds) {}

    private Fixture fixture(String prefix, String code, int memberCount) throws Exception {
        return fixture(prefix, code, memberCount, false);
    }

    private Fixture fixture(String prefix, String code, int memberCount, boolean approval) throws Exception {
        MockHttpSession leader = register(prefix + "lead", prefix + "组长");
        long leaderId = myId(leader);
        TeamCtx team = createTeam(leader, prefix + "组", code, approval);
        MockHttpSession po = register(prefix + "po", prefix + "PO");
        long poId = myId(po);
        if (!approval) {
            join(po, team.invite());
            setRole(leader, team.teamId(), poId, "PO");
        }
        List<MockHttpSession> members = new ArrayList<>();
        List<Long> memberIds = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            MockHttpSession m = register(prefix + "m" + i, prefix + "成员" + i);
            memberIds.add(myId(m));
            if (!approval) join(m, team.invite());
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

    private MvcResult genReport(MockHttpSession s, long teamId, String json) throws Exception {
        return mvc.perform(post("/api/v1/teams/" + teamId + "/reports/generate").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    // ---------- 四、认领池可见性（后端实证） ----------

    @Test
    void poolVisibility_leaderDefectAndStory_memberSeesBoth_inviteVisibleToAll() throws Exception {
        Fixture f = fixture("pv", "PVS", 1);
        long teamId = f.team().teamId();
        long memberId = f.memberIds().get(0);

        // 组长以 destination=POOL 新建 DEFECT
        long defectId = createItem(f.leader(), teamId,
                "{\"type\":\"DEFECT\",\"title\":\"组长建的池缺陷\",\"destination\":\"POOL\"}");
        // 挂任务的 STORY：任务与故事按修订一仅 PO 可建，由 PO 建（故事 destination=POOL）
        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"池任务\"}");
        long storyId = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"池故事\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"DEV\",\"destination\":\"POOL\"}");
        // INVITE 事项：邀请对象是 PO，普通成员并非被邀请人
        long inviteId = createItem(f.leader(), teamId,
                "{\"type\":\"DEFECT\",\"title\":\"邀请认领缺陷\",\"destination\":\"INVITE\",\"inviteeIds\":[" + f.poId() + "]}");

        MvcResult r = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.members().get(0))
                        .param("status", "UNCLAIMED").param("scope", "all"))
                .andExpect(status().isOk()).andReturn();
        List<Object> ids = JsonPath.read(r.getResponse().getContentAsString(), "$[*].id");
        List<Long> seen = ids.stream().map(o -> ((Number) o).longValue()).toList();
        assertThat(seen).contains(defectId, storyId, inviteId);
        assertThat(seen).doesNotContain(taskId); // 任务状态为 NOT_STARTED，不在待认领列表
        assertThat(memberId).isNotEqualTo(f.poId());
    }

    // ---------- 一、报告：唯一性 / 可见过滤 / 生成权限 / 未来周期 ----------

    @Test
    void reportUniquenessVisibilityAndGeneratePermissions() throws Exception {
        Fixture f = fixture("ru", "RUS", 2);
        long teamId = f.team().teamId();
        LocalDate from = LocalDate.now().minusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate to = from.plusDays(6);
        String weekly = "{\"kind\":\"WEEKLY\",\"periodStart\":\"" + from + "\",\"periodEnd\":\"" + to + "\"}";

        MvcResult first = genReport(f.leader(), teamId, weekly);
        assertThat(Integer.valueOf(first.getResponse().getStatus())).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(first.getResponse().getContentAsString(), "$.existed")).isFalse();
        long weeklyId = ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.id")).longValue();

        // 同周期再生成 → 200 existed=true，不新建
        MvcResult again = genReport(f.po(), teamId, weekly);
        assertThat(Integer.valueOf(again.getResponse().getStatus())).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(again.getResponse().getContentAsString(), "$.existed")).isTrue();
        assertThat(((Number) JsonPath.read(again.getResponse().getContentAsString(), "$.id")).longValue()).isEqualTo(weeklyId);

        // 成员无权生成周报/风险；有权生成我的周报
        assertThat(genReport(f.members().get(0), teamId, weekly).getResponse().getStatus()).isEqualTo(403);
        assertThat(genReport(f.members().get(0), teamId, "{\"kind\":\"RISK_SUMMARY\"}").getResponse().getStatus()).isEqualTo(403);
        MvcResult personal = genReport(f.members().get(0), teamId,
                "{\"kind\":\"PERSONAL_WEEKLY\",\"periodStart\":\"" + from + "\",\"periodEnd\":\"" + to + "\"}");
        assertThat(Integer.valueOf(personal.getResponse().getStatus())).isEqualTo(201);

        // 风险摘要组长可生成（当日）
        MvcResult risk = genReport(f.leader(), teamId, "{\"kind\":\"RISK_SUMMARY\"}");
        assertThat(Integer.valueOf(risk.getResponse().getStatus())).isEqualTo(201);
        long riskId = ((Number) JsonPath.read(risk.getResponse().getContentAsString(), "$.id")).longValue();

        // 列表服务端过滤：成员看不到风险摘要与他人个人周报；组长看不到成员的个人周报；PO 能看风险
        MvcResult memberList = mvc.perform(get("/api/v1/teams/" + teamId + "/reports").session(f.members().get(0)))
                .andExpect(status().isOk()).andReturn();
        List<Object> memberKinds = JsonPath.read(memberList.getResponse().getContentAsString(), "$[*].kind");
        assertThat(memberKinds).contains("WEEKLY", "PERSONAL_WEEKLY").doesNotContain("RISK_SUMMARY");
        MvcResult leaderList = mvc.perform(get("/api/v1/teams/" + teamId + "/reports").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        List<Object> leaderIds = JsonPath.read(leaderList.getResponse().getContentAsString(), "$[*].id");
        assertThat(leaderIds.stream().map(o -> ((Number) o).longValue())).contains(weeklyId, riskId)
                .doesNotContain(((Number) JsonPath.read(personal.getResponse().getContentAsString(), "$.id")).longValue());
        // 成员直接取风险摘要 → 404（不可见即不存在）
        mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + riskId).session(f.members().get(0)))
                .andExpect(status().isNotFound());
        // 非成员 → 404
        MockHttpSession outsider = register("ruout", "局外人");
        mvc.perform(get("/api/v1/teams/" + teamId + "/reports").session(outsider)).andExpect(status().isNotFound());

        // 未来周期 → 400
        LocalDate future = LocalDate.now().plusDays(7);
        assertThat(genReport(f.leader(), teamId,
                "{\"kind\":\"STANDUP_SUMMARY\",\"periodStart\":\"" + future + "\"}").getResponse().getStatus()).isEqualTo(400);
    }

    // ---------- 一、报告：编辑/版本/节流/墓碑/定时跳过 ----------

    @Test
    void reportEditRegenVersionsTrashAndTombstone() throws Exception {
        Fixture f = fixture("rv", "RVS", 1);
        long teamId = f.team().teamId();
        LocalDate date = LocalDate.now().minusDays(1);
        MvcResult gen = genReport(f.leader(), teamId,
                "{\"kind\":\"STANDUP_SUMMARY\",\"periodStart\":\"" + date + "\"}");
        assertThat(Integer.valueOf(gen.getResponse().getStatus())).isEqualTo(201);
        String genBody = gen.getResponse().getContentAsString();
        long reportId = ((Number) JsonPath.read(genBody, "$.id")).longValue();
        int v0 = ((Number) JsonPath.read(genBody, "$.version")).intValue();

        // 成员无编辑权（站会=组长/PO）
        mvc.perform(patch("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMd\":\"x\",\"version\":" + v0 + "}"))
                .andExpect(status().isForbidden());
        // 组长编辑 → editedByHuman=true；旧版本再提交 → 409
        MvcResult patched = mvc.perform(patch("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMd\":\"# 人工版\\n\\n手工内容\",\"version\":" + v0 + "}"))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(patched.getResponse().getContentAsString(), "$.editedByHuman")).isTrue();
        mvc.perform(patch("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentMd\":\"y\",\"version\":" + v0 + "}"))
                .andExpect(status().isConflict());

        // 刚生成 <60 秒 → 重新生成 409 REGEN_TOO_FREQUENT
        mvc.perform(post("/api/v1/teams/" + teamId + "/reports/" + reportId + "/regenerate").session(f.leader()).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGEN_TOO_FREQUENT"));

        // 把 last_generated_at 拨到 2 分钟前 → 重新生成成功，旧内容入历史版本
        Report r = reportRepo.findById(reportId).orElseThrow();
        r.setLastGeneratedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        reportRepo.save(r);
        MvcResult regen = mvc.perform(post("/api/v1/teams/" + teamId + "/reports/" + reportId + "/regenerate")
                        .session(f.leader()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(regen.getResponse().getContentAsString(), "$.editedByHuman")).isFalse();
        MvcResult versions = mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId + "/versions")
                        .session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        List<Object> vIds = JsonPath.read(versions.getResponse().getContentAsString(), "$[*].id");
        assertThat(vIds).hasSize(1);
        long versionId = ((Number) vIds.get(0)).longValue();
        MvcResult vFull = mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId + "/versions/" + versionId)
                        .session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(vFull.getResponse().getContentAsString(), "$.content")).contains("人工版");

        // 恢复历史版本 → 内容回人工版，editedByHuman=true，且恢复前内容也存版（共 2 个版本）
        MvcResult restored = mvc.perform(post("/api/v1/teams/" + teamId + "/reports/" + reportId
                        + "/versions/" + versionId + "/restore").session(f.leader()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(restored.getResponse().getContentAsString(), "$.content")).contains("人工版");
        assertThat((Boolean) JsonPath.read(restored.getResponse().getContentAsString(), "$.editedByHuman")).isTrue();
        MvcResult versions2 = mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId + "/versions")
                        .session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) JsonPath.read(versions2.getResponse().getContentAsString(), "$[*].id")).hasSize(2);

        // 删除 → 列表消失、回收站可见；同周期手动 generate → Q1（M4 待问结论）：有意恢复，原地重生
        mvc.perform(delete("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()))
                .andExpect(status().isNotFound());
        MvcResult trash = mvc.perform(get("/api/v1/teams/" + teamId + "/reports/trash").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) JsonPath.read(trash.getResponse().getContentAsString(), "$[*].id")).hasSize(1);
        MvcResult regenAfterDelete = genReport(f.leader(), teamId,
                "{\"kind\":\"STANDUP_SUMMARY\",\"periodStart\":\"" + date + "\"}");
        assertThat(Integer.valueOf(regenAfterDelete.getResponse().getStatus())).isEqualTo(201);
        assertThat((Boolean) JsonPath.read(regenAfterDelete.getResponse().getContentAsString(), "$.existed")).isFalse();
        assertThat((Boolean) JsonPath.read(regenAfterDelete.getResponse().getContentAsString(), "$.restored")).isTrue();

        // Q1 恢复后直接可见（已不在回收站）；审计日志已写入
        mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()))
                .andExpect(status().isOk());
        assertThat(em.createQuery("select count(a) from AuditLog a where a.action = 'REPORT_REGENERATE_FROM_TRASH'", Long.class)
                .getSingleResult()).isEqualTo(1L);
    }

    @Test
    void autoGenerateSkipsExistingAndTombstone() throws Exception {
        Fixture f = fixture("ag", "AGS", 0);
        long teamId = f.team().teamId();
        LocalDate today = LocalDate.now();
        int created = reportService.autoGenerateDaily(today);
        assertThat(created).isEqualTo(2); // 站会 + 风险
        assertThat(reportService.autoGenerateDaily(today)).isEqualTo(0);
        // 删除风险摘要后，定时任务不得再生成（墓碑）
        MvcResult list = mvc.perform(get("/api/v1/teams/" + teamId + "/reports").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        List<Object> ids = JsonPath.read(list.getResponse().getContentAsString(), "$[*].id");
        List<String> kinds = JsonPath.read(list.getResponse().getContentAsString(), "$[*].kind");
        long riskId = ((Number) ids.get(kinds.indexOf("RISK_SUMMARY"))).longValue();
        mvc.perform(delete("/api/v1/teams/" + teamId + "/reports/" + riskId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(reportService.autoGenerateDaily(today)).isEqualTo(0);
    }

    // ---------- 二、幂等 ----------

    @Test
    void idempotencyKey_itemsReportsBatchesAndClose() throws Exception {
        Fixture f = fixture("id", "IDS", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        // POST /items 同键两次 → 只建一条
        String itemJson = "{\"type\":\"DEFECT\",\"title\":\"幂等缺陷\",\"destination\":\"POOL\"}";
        MvcResult i1 = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-item-1").contentType(MediaType.APPLICATION_JSON).content(itemJson))
                .andExpect(status().isCreated()).andReturn();
        MvcResult i2 = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-item-1").contentType(MediaType.APPLICATION_JSON).content(itemJson))
                .andExpect(status().isCreated()).andReturn();
        assertThat((Object) JsonPath.read(i2.getResponse().getContentAsString(), "$.id"))
                .isEqualTo(JsonPath.read(i1.getResponse().getContentAsString(), "$.id"));
        MvcResult items = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) JsonPath.read(items.getResponse().getContentAsString(), "$[*].id")).hasSize(1);

        // POST /reports/generate 同键两次 → 同一份
        LocalDate from = LocalDate.now().minusWeeks(1).with(DayOfWeek.MONDAY);
        String weekly = "{\"kind\":\"WEEKLY\",\"periodStart\":\"" + from + "\",\"periodEnd\":\"" + from.plusDays(6) + "\"}";
        MvcResult g1 = mvc.perform(post("/api/v1/teams/" + teamId + "/reports/generate").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-rep-1").contentType(MediaType.APPLICATION_JSON).content(weekly))
                .andExpect(status().isCreated()).andReturn();
        MvcResult g2 = mvc.perform(post("/api/v1/teams/" + teamId + "/reports/generate").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-rep-1").contentType(MediaType.APPLICATION_JSON).content(weekly))
                .andExpect(status().isCreated()).andReturn();
        assertThat((Object) JsonPath.read(g2.getResponse().getContentAsString(), "$.id"))
                .isEqualTo(JsonPath.read(g1.getResponse().getContentAsString(), "$.id"));

        // POST /dispatch-batches 同键两次 → 同一批次，事项不翻倍
        String batch = "{\"type\":\"ADHOC\",\"title\":\"每人一份\",\"memberIds\":[" + m0 + "]}";
        MvcResult b1 = mvc.perform(post("/api/v1/teams/" + teamId + "/dispatch-batches").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-batch-1").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isCreated()).andReturn();
        MvcResult b2 = mvc.perform(post("/api/v1/teams/" + teamId + "/dispatch-batches").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-batch-1").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isCreated()).andReturn();
        assertThat((Object) JsonPath.read(b2.getResponse().getContentAsString(), "$.batchId"))
                .isEqualTo(JsonPath.read(b1.getResponse().getContentAsString(), "$.batchId"));
        MvcResult items2 = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) JsonPath.read(items2.getResponse().getContentAsString(), "$[*].id")).hasSize(2);

        // POST /iterations/{id}/close 同键两次 + 无键再 close → 改进项只生成一次，重复返回 existing=true
        LocalDate today = LocalDate.now();
        MvcResult it = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"幂等迭代\",\"startDate\":\"" + today.minusDays(2) + "\",\"endDate\":\"" + today.plusDays(5) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        long itId = ((Number) JsonPath.read(it.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/start").session(f.leader()).with(csrf()))
                .andExpect(status().isOk());
        String closeBody = "{\"decisions\":[],\"improvements\":[\"改进一\"],\"createNextIteration\":true}";
        MvcResult c1 = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/close").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-close-1").contentType(MediaType.APPLICATION_JSON).content(closeBody))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(c1.getResponse().getContentAsString(), "$.existing")).isFalse();
        MvcResult c2 = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/close").session(f.leader()).with(csrf())
                        .header("Idempotency-Key", "k-close-1").contentType(MediaType.APPLICATION_JSON).content(closeBody))
                .andExpect(status().isOk()).andReturn();
        assertThat((Object) JsonPath.read(c2.getResponse().getContentAsString(), "$.reportId"))
                .isEqualTo(JsonPath.read(c1.getResponse().getContentAsString(), "$.reportId"));
        MvcResult c3 = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/close").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(closeBody))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(c3.getResponse().getContentAsString(), "$.existing")).isTrue();
        MvcResult all = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        List<String> titles = JsonPath.read(all.getResponse().getContentAsString(), "$[*].title");
        assertThat(titles.stream().filter(t -> t.equals("改进一")).count()).isEqualTo(1);
    }

    // ---------- 三、其余权限点 ----------

    @Test
    void taskAndStoryDeleteRules() throws Exception {
        Fixture f = fixture("td", "TDS", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);
        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"删除任务\"}");
        long storyId = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"未开始故事\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"DEV\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        // 成员删故事 → 403；组长删未开始故事 → 204；此后任务下无故事，PO 可删任务
        mvc.perform(delete("/api/v1/teams/" + teamId + "/items/" + storyId).session(f.members().get(0)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/teams/" + teamId + "/items/" + storyId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/teams/" + teamId + "/items/" + taskId).session(f.po()).with(csrf()))
                .andExpect(status().isNoContent());

        // 已开始的故事：任务与故事都删不了
        long task2 = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"进行中任务\"}");
        long story2 = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"已开始故事\",\"parentId\":" + task2
                        + ",\"storyCategory\":\"DEV\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + story2 + "/progress").session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"开始干活\"}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/teams/" + teamId + "/items/" + story2).session(f.leader()).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/teams/" + teamId + "/items/" + task2).session(f.po()).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void storyPatchOwnerDescriptionOnly() throws Exception {
        Fixture f = fixture("sp", "SPS", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);
        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"故事父任务\"}");
        long storyId = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"原标题\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"DEV\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        int v = itemVersion(f.members().get(0), teamId, storyId);
        // 负责人改标题 → 403；只改描述 → 200
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + storyId).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v + ",\"title\":\"新标题\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + storyId).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v + ",\"description\":\"负责人改的描述\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("负责人改的描述"));
        // PO 全改
        int v2 = itemVersion(f.po(), teamId, storyId);
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + storyId).session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v2 + ",\"title\":\"PO 改的标题\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("PO 改的标题"));
    }

    @Test
    void commentEditWindowAndDelete() throws Exception {
        Fixture f = fixture("cm", "CMS", 2);
        long teamId = f.team().teamId();
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"评论事项\",\"destination\":\"POOL\"}");
        MvcResult c = mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/comments")
                        .session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"第一条评论\"}"))
                .andExpect(status().isCreated()).andReturn();
        long commentId = ((Number) JsonPath.read(c.getResponse().getContentAsString(), "$.id")).longValue();
        String base = "/api/v1/teams/" + teamId + "/items/" + itemId + "/comments/" + commentId;
        // 他人改 → 403；作者 15 分钟内改 → 200
        mvc.perform(patch(base).session(f.members().get(1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"改别人的\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(base).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"作者改过的\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("作者改过的"));
        // 拨到 20 分钟前 → 超时 403（JPQL 更新 + 清一级缓存，确保服务层读到新值）
        em.createQuery("update Comment c set c.createdAt = :t where c.id = :id")
                .setParameter("t", Instant.now().minus(20, ChronoUnit.MINUTES))
                .setParameter("id", commentId).executeUpdate();
        em.flush();
        em.clear();
        mvc.perform(patch(base).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"超时改\"}"))
                .andExpect(status().isForbidden());
        // 他人删 → 403；组长删 → 204；删后列表为空
        mvc.perform(delete(base).session(f.members().get(1)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete(base).session(f.leader()).with(csrf())).andExpect(status().isNoContent());
        MvcResult list = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId + "/comments").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) JsonPath.read(list.getResponse().getContentAsString(), "$[*].id")).isEmpty();
    }

    @Test
    void savedFilterLimit20() throws Exception {
        Fixture f = fixture("sf", "SFS", 0);
        long teamId = f.team().teamId();
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/teams/" + teamId + "/saved-filters").session(f.leader()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"筛选" + i + "\",\"criteria\":{}}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/teams/" + teamId + "/saved-filters").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第21个\",\"criteria\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void labelManagementRules() throws Exception {
        Fixture f = fixture("lb", "LBS", 1);
        long teamId = f.team().teamId();
        String base = "/api/v1/teams/" + teamId + "/labels";
        mvc.perform(post(base).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"紧急\"}"))
                .andExpect(status().isForbidden());
        MvcResult created = mvc.perform(post(base).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"紧急\",\"color\":\"red\"}"))
                .andExpect(status().isCreated()).andReturn();
        long labelId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(post(base).session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"紧急\"}"))
                .andExpect(status().isConflict());
        // 在用：建事项挂此标签 → 删除 409 LABEL_IN_USE 附使用数；force=true 才删
        createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"带标签事项\",\"destination\":\"POOL\",\"labelNames\":[\"紧急\"]}");
        MvcResult inUse = mvc.perform(delete(base + "/" + labelId).session(f.leader()).with(csrf())).andReturn();
        assertThat(Integer.valueOf(inUse.getResponse().getStatus())).isEqualTo(409);
        assertThat((String) JsonPath.read(inUse.getResponse().getContentAsString(), "$.code")).isEqualTo("LABEL_IN_USE");
        assertThat(((Number) JsonPath.read(inUse.getResponse().getContentAsString(), "$.detail.usageCount")).intValue()).isGreaterThanOrEqualTo(1);
        mvc.perform(delete(base + "/" + labelId).session(f.leader()).with(csrf()).param("force", "true"))
                .andExpect(status().isNoContent());
    }

    @Test
    void joinRequestDuplicatePending409() throws Exception {
        MockHttpSession leader = register("jrlead", "审批组长");
        TeamCtx team = createTeam(leader, "审批组", "JRS", true);
        MockHttpSession applicant = register("jrapp", "申请人");
        mvc.perform(post("/api/v1/teams/join").session(applicant).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + team.invite() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(false));
        mvc.perform(post("/api/v1/teams/join").session(applicant).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + team.invite() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void iterationCreateLeaderOnlyAndSinglePlanning() throws Exception {
        Fixture f = fixture("ic", "ICS", 0);
        long teamId = f.team().teamId();
        LocalDate today = LocalDate.now();
        String body = "{\"name\":\"迭代甲\",\"startDate\":\"" + today + "\",\"endDate\":\"" + today.plusDays(13) + "\"}";
        mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"迭代乙\",\"startDate\":\"" + today.plusDays(14) + "\",\"endDate\":\"" + today.plusDays(27) + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void keyDatePermissionsAndNudgeOwnAndInviteCodeVisibility() throws Exception {
        Fixture f = fixture("kd", "KDS", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);
        LocalDate today = LocalDate.now();
        MvcResult it = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"关键日期迭代\",\"startDate\":\"" + today + "\",\"endDate\":\"" + today.plusDays(13) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        long itId = ((Number) JsonPath.read(it.getResponse().getContentAsString(), "$.id")).longValue();
        String kdBody = "{\"title\":\"封版\",\"date\":\"" + today.plusDays(9) + "\",\"category\":\"FREEZE\"}";
        mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/key-dates").session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(kdBody))
                .andExpect(status().isForbidden());
        MvcResult kd = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/key-dates").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(kdBody))
                .andExpect(status().isCreated()).andReturn();
        long kdId = ((Number) JsonPath.read(kd.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(patch("/api/v1/teams/" + teamId + "/iterations/" + itId + "/key-dates/" + kdId).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"改名\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/teams/" + teamId + "/iterations/" + itId + "/key-dates/" + kdId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"封版日\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("封版日"));
        mvc.perform(delete("/api/v1/teams/" + teamId + "/key-dates/" + kdId).session(f.members().get(0)).with(csrf()))
                .andExpect(status().isForbidden());

        // 催一下：不能催自己负责的事项 → 400
        long own = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"我负责的\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + own + "/nudge").session(f.members().get(0)).with(csrf()))
                .andExpect(status().isBadRequest());

        // 邀请码：成员在 /teams/mine 看不到完整码，组长可见；重置仅组长
        MvcResult mineMember = mvc.perform(get("/api/v1/teams/mine").session(f.members().get(0)))
                .andExpect(status().isOk()).andReturn();
        assertThat((Object) JsonPath.read(mineMember.getResponse().getContentAsString(), "$[0].inviteCode")).isNull();
        MvcResult mineLeader = mvc.perform(get("/api/v1/teams/mine").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(mineLeader.getResponse().getContentAsString(), "$[0].inviteCode")).isNotBlank();
        mvc.perform(post("/api/v1/teams/" + teamId + "/invite-code/reset").session(f.members().get(0)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/teams/" + teamId + "/invite-code/reset").session(f.leader()).with(csrf()))
                .andExpect(status().isOk());
    }
}
