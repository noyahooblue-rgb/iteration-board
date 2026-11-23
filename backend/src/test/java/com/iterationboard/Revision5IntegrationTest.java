package com.iterationboard;

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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 修订五后端规则测试（状态×进度联动 + 状态一致性）：
 * R5-T1 提交验收补满 100% 且自动清阻塞；R5-T2 释放清零进度；R5-T3 退回保持进度 + 已退回派生标记；
 * R5-T4 UNSTART 撤销自动开始；R5-T5 已完成事项编辑限制；R5-T6 父子冲突禁止验收任务；
 * R5-T7 任务进度故事点加权；R5-T8 并发版本冲突 409。
 * 全部测试数据为虚构账号与中性示例事项。MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Revision5IntegrationTest {

    @Autowired MockMvc mvc;

    // ---------- helpers（与 M2 同口径） ----------

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
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(true));
    }

    private void setRole(MockHttpSession leader, long teamId, long userId, String role) throws Exception {
        mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + userId + "/role").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isNoContent());
    }

    private long createItem(MockHttpSession s, long teamId, String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String getItemJson(MockHttpSession s, long teamId, long itemId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(s))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    private int version(MockHttpSession s, long teamId, long itemId) throws Exception {
        return ((Number) JsonPath.read(getItemJson(s, teamId, itemId), "$.version")).intValue();
    }

    private MvcResult transition(MockHttpSession s, long teamId, long itemId, String action, int v, String extra) throws Exception {
        return mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/transitions").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"version\":" + v + (extra == null ? "" : "," + extra) + "}"))
                .andReturn();
    }

    private void progress(MockHttpSession s, long teamId, long itemId, String json) throws Exception {
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/progress").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    private record Fixture(MockHttpSession leader, MockHttpSession po, long poId, TeamCtx team,
                           List<MockHttpSession> members, List<Long> memberIds) {}

    private Fixture fixture(String prefix, String code, int memberCount) throws Exception {
        MockHttpSession leader = register(prefix + "lead", prefix + "组长");
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
        return new Fixture(leader, po, poId, team, members, memberIds);
    }

    /** 组长建池事项（验收人默认=组长），成员认领并登记进展到指定进度。 */
    private long claimedInProgress(Fixture f, long teamId, int percent) throws Exception {
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"示例事项\",\"destination\":\"POOL\"}");
        MockHttpSession m = f.members().get(0);
        transition(m, teamId, itemId, "CLAIM", version(m, teamId, itemId), null);
        progress(m, teamId, itemId, "{\"text\":\"推进中\",\"percent\":" + percent + "}");
        return itemId;
    }

    // ---------- R5-T1：提交验收补满 100% + 自动清阻塞 ----------

    @Test
    void submitFillsProgressAndClearsBlocked() throws Exception {
        Fixture f = fixture("r5a", "RFA", 1);
        long teamId = f.team().teamId();
        MockHttpSession m = f.members().get(0);
        long itemId = claimedInProgress(f, teamId, 40);
        progress(m, teamId, itemId, "{\"text\":\"卡住了\",\"percent\":40,\"blocked\":true,\"blockReason\":\"等示例资料\"}");
        String blocked = getItemJson(m, teamId, itemId);
        assertThat((Boolean) JsonPath.read(blocked, "$.blocked")).isTrue();

        MvcResult sub = transition(m, teamId, itemId, "SUBMIT", version(m, teamId, itemId), null);
        assertThat(sub.getResponse().getStatus()).isEqualTo(200);
        String body = sub.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("IN_REVIEW");
        assertThat(((Number) JsonPath.read(body, "$.progressPercent")).intValue()).isEqualTo(100);
        assertThat((Boolean) JsonPath.read(body, "$.blocked")).isFalse();

        // 历史留痕：动态里有 UNBLOCK
        MvcResult acts = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId + "/activities").session(m))
                .andExpect(status().isOk()).andReturn();
        assertThat(acts.getResponse().getContentAsString()).contains("UNBLOCK");
    }

    // ---------- R5-T2：释放清零进度 ----------

    @Test
    void releaseZeroesProgress() throws Exception {
        Fixture f = fixture("r5b", "RFB", 1);
        long teamId = f.team().teamId();
        MockHttpSession m = f.members().get(0);
        long itemId = claimedInProgress(f, teamId, 60);
        MvcResult rel = transition(m, teamId, itemId, "RELEASE", version(m, teamId, itemId), "\"reason\":\"排期调整\"");
        assertThat(rel.getResponse().getStatus()).isEqualTo(200);
        String body = rel.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("UNCLAIMED");
        assertThat(((Number) JsonPath.read(body, "$.progressPercent")).intValue()).isEqualTo(0);
    }

    // ---------- R5-T3：退回保持进度 + 已退回派生标记 ----------

    @Test
    void rejectKeepsProgressAndDerivesMarker() throws Exception {
        Fixture f = fixture("r5c", "RFC", 1);
        long teamId = f.team().teamId();
        MockHttpSession m = f.members().get(0);
        long itemId = claimedInProgress(f, teamId, 70);
        transition(m, teamId, itemId, "SUBMIT", version(m, teamId, itemId), null);
        MvcResult rej = transition(f.leader(), teamId, itemId, "REJECT",
                version(f.leader(), teamId, itemId), "\"reason\":\"示例退回意见\"");
        assertThat(rej.getResponse().getStatus()).isEqualTo(200);
        String body = rej.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) JsonPath.read(body, "$.progressPercent")).intValue()).isEqualTo(100);
        assertThat((Boolean) JsonPath.read(body, "$.rejected")).isTrue();
        assertThat((String) JsonPath.read(body, "$.rejectReason")).isEqualTo("示例退回意见");

        // 再次提交后标记消失
        transition(m, teamId, itemId, "SUBMIT", version(m, teamId, itemId), null);
        String after = getItemJson(m, teamId, itemId);
        assertThat((Boolean) JsonPath.read(after, "$.rejected")).isFalse();
    }

    // ---------- R5-T4：UNSTART 撤销自动开始 ----------

    @Test
    void unstartRevertsAutoStart() throws Exception {
        Fixture f = fixture("r5d", "RFD", 1);
        long teamId = f.team().teamId();
        MockHttpSession m = f.members().get(0);
        long m0 = f.memberIds().get(0);
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"被指派示例\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        progress(m, teamId, itemId, "{\"text\":\"开始推进\",\"percent\":30}");
        String started = getItemJson(m, teamId, itemId);
        assertThat((String) JsonPath.read(started, "$.status")).isEqualTo("IN_PROGRESS");

        MvcResult un = transition(m, teamId, itemId, "UNSTART", version(m, teamId, itemId), null);
        assertThat(un.getResponse().getStatus()).isEqualTo(200);
        String body = un.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("NOT_STARTED");
        assertThat(((Number) JsonPath.read(body, "$.progressPercent")).intValue()).isEqualTo(0);
    }

    // ---------- R5-T5：已完成事项只能改描述 ----------

    @Test
    void doneItemEditLocked() throws Exception {
        Fixture f = fixture("r5e", "RFE", 1);
        long teamId = f.team().teamId();
        MockHttpSession m = f.members().get(0);
        long itemId = claimedInProgress(f, teamId, 100);
        transition(m, teamId, itemId, "SUBMIT", version(m, teamId, itemId), null);
        transition(f.leader(), teamId, itemId, "APPROVE", version(f.leader(), teamId, itemId), null);
        String done = getItemJson(m, teamId, itemId);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("DONE");
        int v = ((Number) JsonPath.read(done, "$.version")).intValue();

        MvcResult titlePatch = mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v + ",\"title\":\"改个标题\"}"))
                .andReturn();
        assertThat(titlePatch.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) JsonPath.read(titlePatch.getResponse().getContentAsString(), "$.code")).isEqualTo("DONE_ITEM_LOCKED");

        MvcResult descPatch = mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v + ",\"description\":\"补充说明文字\"}"))
                .andReturn();
        assertThat(descPatch.getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- R5-T6/T7：任务——故事未全完成禁止验收 + 进度故事点加权 ----------

    @Test
    void taskApproveGuardAndWeightedProgress() throws Exception {
        Fixture f = fixture("r5f", "RFF", 1);
        long teamId = f.team().teamId();
        MockHttpSession m = f.members().get(0);
        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"示例任务\",\"destination\":\"POOL\"}");
        long s1 = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"示例故事一\",\"destination\":\"POOL\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"DEV\",\"estimate\":3}");
        long s2 = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"示例故事二\",\"destination\":\"POOL\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"TEST\",\"estimate\":1}");

        // 故事未全部完成时验收任务 → 422（推算状态未到待验收，流转非法）
        MvcResult early = transition(f.po(), teamId, taskId, "APPROVE", version(f.po(), teamId, taskId), null);
        assertThat(early.getResponse().getStatus()).isEqualTo(422);

        // 完成 3 点故事 → 任务进度按故事点加权 3/4=75，进行中
        transition(m, teamId, s1, "CLAIM", version(m, teamId, s1), null);
        progress(m, teamId, s1, "{\"text\":\"做完了\",\"percent\":100}");
        transition(m, teamId, s1, "SUBMIT", version(m, teamId, s1), null);
        String st1 = getItemJson(m, teamId, s1);
        if ("IN_REVIEW".equals(JsonPath.read(st1, "$.status"))) {
            transition(f.po(), teamId, s1, "APPROVE", version(f.po(), teamId, s1), null);
        }
        String t1 = getItemJson(f.po(), teamId, taskId);
        assertThat((String) JsonPath.read(t1, "$.taskStatus")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) JsonPath.read(t1, "$.progressPercent")).intValue()).isEqualTo(75);

        // 完成 1 点故事 → 全部完成待验收 → PO 验收任务成功
        transition(m, teamId, s2, "CLAIM", version(m, teamId, s2), null);
        progress(m, teamId, s2, "{\"text\":\"做完了\",\"percent\":100}");
        transition(m, teamId, s2, "SUBMIT", version(m, teamId, s2), null);
        String st2 = getItemJson(m, teamId, s2);
        if ("IN_REVIEW".equals(JsonPath.read(st2, "$.status"))) {
            transition(f.po(), teamId, s2, "APPROVE", version(f.po(), teamId, s2), null);
        }
        String t2 = getItemJson(f.po(), teamId, taskId);
        assertThat((String) JsonPath.read(t2, "$.taskStatus")).isEqualTo("PENDING_ACCEPTANCE");
        MvcResult ap = transition(f.po(), teamId, taskId, "APPROVE", version(f.po(), teamId, taskId), null);
        assertThat(ap.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(ap.getResponse().getContentAsString(), "$.taskStatus")).isEqualTo("DONE");
    }

    // ---------- R5-T8：并发修改版本冲突 ----------

    @Test
    void staleVersionConflict() throws Exception {
        Fixture f = fixture("r5g", "RFG", 1);
        long teamId = f.team().teamId();
        MockHttpSession m = f.members().get(0);
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"并发示例\",\"destination\":\"POOL\"}");
        MvcResult stale = transition(m, teamId, itemId, "CLAIM", 999, null);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat((String) JsonPath.read(stale.getResponse().getContentAsString(), "$.code")).isEqualTo("VERSION_CONFLICT");
    }
}
