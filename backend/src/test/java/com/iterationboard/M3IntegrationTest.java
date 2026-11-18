package com.iterationboard;

import com.iterationboard.repo.ItemRepo;
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
 * M3 集成测试（协同）：待问 1–8 落实（任务打回、共同负责转交只换一人、容量系数、
 * 小组设置/归档/退出/成员状态、单事项催一下、Item.inviteeIds）+ 通知中心与偏好、
 * 迭代全生命周期与燃尽、自动提醒扫描、站会/总览、报告与导出。
 * MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class M3IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ItemRepo itemRepo;

    // ---------- helpers（与 M2 测试同构） ----------

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
                                + "\",\"password\":\"passw0rd1\",\"captchaId\":\"" + c[0]
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

    private String notificationsJson(MockHttpSession s, long teamId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/notifications").session(s).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
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

    // ---------- 1. 小组设置 / 成员状态 / 退出 / 归档（待问 7a） ----------

    @Test
    void teamPatchStatusLeaveArchive() throws Exception {
        Fixture f = fixture("tp", "TPA", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        // PATCH 小组：名称 + 描述 + 设置（容量系数 2）
        MvcResult patched = mvc.perform(patch("/api/v1/teams/" + teamId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名后的组\",\"description\":\"小组说明\",\"settings\":{\"capacityPointsPerWorkday\":2}}"))
                .andExpect(status().isOk()).andReturn();
        String body = patched.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("改名后的组");
        assertThat((String) JsonPath.read(body, "$.description")).isEqualTo("小组说明");
        assertThat(((Number) JsonPath.read(body, "$.settings.capacityPointsPerWorkday")).doubleValue()).isEqualTo(2.0);
        // 默认阈值带出
        assertThat(((Number) JsonPath.read(body, "$.settings.reminderThresholds.staleInProgressWorkdays")).intValue()).isEqualTo(2);

        // 普通成员 PATCH 小组 → 403
        mvc.perform(patch("/api/v1/teams/" + teamId).session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());

        // 成员状态：自己设请假（含起止），组长可代设，普通成员代设他人 → 403
        LocalDate start = LocalDate.now().plusDays(1);
        LocalDate end = LocalDate.now().plusDays(3);
        MvcResult st = mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + m0 + "/status")
                        .session(f.members().get(0)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberStatus\":\"LEAVE\",\"memberStatusStart\":\"" + start + "\",\"memberStatusEnd\":\"" + end + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(st.getResponse().getContentAsString(), "$.memberStatus")).isEqualTo("LEAVE");
        assertThat((String) JsonPath.read(st.getResponse().getContentAsString(), "$.memberStatusStart")).isEqualTo(start.toString());
        mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + f.poId() + "/status")
                        .session(f.members().get(0)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberStatus\":\"TRAVEL\"}"))
                .andExpect(status().isForbidden());
        MvcResult backToNormal = mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + m0 + "/status")
                        .session(f.leader()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberStatus\":\"NORMAL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberStatus").value("NORMAL")).andReturn();
        // NORMAL 时起止日期被清空（字段可能序列化为 null 或不出现，两种都接受）
        java.util.Map<String, Object> memberMap = JsonPath.read(backToNormal.getResponse().getContentAsString(), "$");
        assertThat(memberMap.get("memberStatusStart")).isNull();
        assertThat(memberMap.get("memberStatusEnd")).isNull();

        // 唯一组长退出 → 400 LAST_LEADER；普通成员退出 → 204，成员列表少一人
        mvc.perform(post("/api/v1/teams/" + teamId + "/leave").session(f.leader()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_LEADER"));
        mvc.perform(post("/api/v1/teams/" + teamId + "/leave").session(f.members().get(0)).with(csrf()))
                .andExpect(status().isNoContent());
        MvcResult members = mvc.perform(get("/api/v1/teams/" + teamId + "/members").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) JsonPath.read(members.getResponse().getContentAsString(), "$")).hasSize(2);

        // 归档
        mvc.perform(post("/api/v1/teams/" + teamId + "/archive").session(f.leader()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    // ---------- 2. 共同负责 TRANSFER 只换一人（待问 2） ----------

    @Test
    void transferReplacesOnlyOneOwner() throws Exception {
        Fixture f = fixture("tr", "TRF", 3);
        long teamId = f.team().teamId();
        long a = f.memberIds().get(0), b = f.memberIds().get(1), c = f.memberIds().get(2);

        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"共同负责事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + a + "," + b + "]}");

        // A 转交给 C（缺省替换发起人本人 A）→ 负责人变为 [B, C]
        MvcResult t1 = transition(f.members().get(0), teamId, itemId, "TRANSFER",
                version(f.members().get(0), teamId, itemId), "\"newAssigneeId\":" + c);
        assertThat(t1.getResponse().getStatus()).isEqualTo(200);
        List<Number> owners1 = JsonPath.read(t1.getResponse().getContentAsString(), "$.ownerIds");
        assertThat(owners1.stream().map(Number::longValue)).containsExactlyInAnyOrder(b, c);

        // 组长对多负责人事项不指定 replaceUserId → 400 REPLACE_REQUIRED
        long d = f.poId();
        MvcResult t2 = transition(f.leader(), teamId, itemId, "TRANSFER",
                version(f.leader(), teamId, itemId), "\"newAssigneeId\":" + d);
        assertThat(t2.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(t2.getResponse().getContentAsString(), "$.code")).isEqualTo("REPLACE_REQUIRED");

        // 组长指定替换 B → 负责人变为 [PO, C]，C 保留
        MvcResult t3 = transition(f.leader(), teamId, itemId, "TRANSFER",
                version(f.leader(), teamId, itemId), "\"newAssigneeId\":" + d + ",\"replaceUserId\":" + b);
        assertThat(t3.getResponse().getStatus()).isEqualTo(200);
        List<Number> owners3 = JsonPath.read(t3.getResponse().getContentAsString(), "$.ownerIds");
        assertThat(owners3.stream().map(Number::longValue)).containsExactlyInAnyOrder(d, c);

        // 转给已是共同负责人的人 → 409 ALREADY_OWNER
        MvcResult t4 = transition(f.leader(), teamId, itemId, "TRANSFER",
                version(f.leader(), teamId, itemId), "\"newAssigneeId\":" + c + ",\"replaceUserId\":" + d);
        assertThat(t4.getResponse().getStatus()).isEqualTo(409);
    }

    // ---------- 3. 任务打回 REJECT（待问 1） ----------

    @Test
    void taskRejectRequiresActionableStory() throws Exception {
        Fixture f = fixture("rj", "RJT", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"被打回的任务\"}");
        long storyId = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"故事一\",\"parentId\":" + taskId + ",\"storyCategory\":\"DEV\","
                        + "\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "],\"acceptorId\":" + f.poId() + "}");

        // 故事走完：进展 → 提交 → PO 通过 → 任务进入待验收
        progress(f.members().get(0), teamId, storyId, "{\"text\":\"做完了\"}");
        transition(f.members().get(0), teamId, storyId, "SUBMIT", version(f.members().get(0), teamId, storyId), null);
        transition(f.po(), teamId, storyId, "APPROVE", version(f.po(), teamId, storyId), null);
        assertThat((String) JsonPath.read(getItemJson(f.po(), teamId, taskId), "$.taskStatus")).isEqualTo("PENDING_ACCEPTANCE");
        assertThat((String) JsonPath.read(getItemJson(f.po(), teamId, taskId), "$.status")).isEqualTo("IN_REVIEW");

        int tv = version(f.po(), teamId, taskId);
        // 无原因打回 → 400
        MvcResult noReason = transition(f.po(), teamId, taskId, "REJECT", tv, null);
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        // 有原因但不给可做之事 → 422 NO_ACTIONABLE_STORY
        MvcResult noAction = transition(f.po(), teamId, taskId, "REJECT", tv, "\"reason\":\"还不够好\"");
        assertThat(noAction.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) JsonPath.read(noAction.getResponse().getContentAsString(), "$.code")).isEqualTo("NO_ACTIONABLE_STORY");
        // 非 PO 打回 → 403
        MvcResult byMember = transition(f.members().get(0), teamId, taskId, "REJECT", tv,
                "\"reason\":\"x\",\"reopenItemIds\":[" + storyId + "]");
        assertThat(byMember.getResponse().getStatus()).isEqualTo(403);

        // 重开已完成故事 → 故事回 IN_PROGRESS，任务回进行中
        MvcResult ok = transition(f.po(), teamId, taskId, "REJECT", tv,
                "\"reason\":\"验收不通过，重开故事一\",\"reopenItemIds\":[" + storyId + "]");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(getItemJson(f.po(), teamId, storyId), "$.status")).isEqualTo("IN_PROGRESS");
        assertThat((String) JsonPath.read(getItemJson(f.po(), teamId, taskId), "$.taskStatus")).isEqualTo("IN_PROGRESS");

        // 再走完一轮，这次打回时新建补充故事
        progress(f.members().get(0), teamId, storyId, "{\"text\":\"改好了\"}");
        transition(f.members().get(0), teamId, storyId, "SUBMIT", version(f.members().get(0), teamId, storyId), null);
        transition(f.po(), teamId, storyId, "APPROVE", version(f.po(), teamId, storyId), null);
        int tv2 = version(f.po(), teamId, taskId);
        MvcResult ok2 = transition(f.po(), teamId, taskId, "REJECT", tv2,
                "\"reason\":\"还要补一个故事\",\"newStories\":[{\"title\":\"补充故事\",\"storyCategory\":\"TEST\"}]");
        assertThat(ok2.getResponse().getStatus()).isEqualTo(200);
        MvcResult stories = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + taskId + "/stories").session(f.po()))
                .andExpect(status().isOk()).andReturn();
        List<String> titles = JsonPath.read(stories.getResponse().getContentAsString(), "$[*].title");
        assertThat(titles).contains("补充故事");
        assertThat((String) JsonPath.read(getItemJson(f.po(), teamId, taskId), "$.taskStatus")).isEqualTo("IN_PROGRESS");
    }

    // ---------- 4. 单事项催一下 + 快捷回应（待问 7b） ----------

    @Test
    void singleItemNudgeAndRespond() throws Exception {
        Fixture f = fixture("ng", "NDG", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"需要更新的事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        progress(f.members().get(0), teamId, itemId, "{\"text\":\"开始处理\"}");

        // 负责人催自己负责的事项 → 400
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/nudge")
                        .session(f.members().get(0)).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_NUDGE_OWN"));

        // 组长催 → 200，nudgeCount=1，被催人收到 NUDGE 通知
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/nudge")
                        .session(f.leader()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nudgedUserIds[0]").value(m0))
                .andExpect(jsonPath("$.nudgeCount").value(1));
        assertThat(notificationsJson(f.members().get(0), teamId)).contains("\"type\":\"NUDGE\"");

        // 同一发起人同一天再催 → 409
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/nudge")
                        .session(f.leader()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NUDGE_DAILY_LIMIT"));

        // 快捷回应 SOON → 发起人收到 NUDGE_RESPONSE；再次回应 → Q13（M4）：幂等安全，返回已回应状态
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/nudge/respond")
                        .session(f.members().get(0)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"SOON\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.response").value("SOON"));
        assertThat(notificationsJson(f.leader(), teamId)).contains("\"type\":\"NUDGE_RESPONSE\"");
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/nudge/respond")
                        .session(f.members().get(0)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"TODAY\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alreadyResponded").value(true))
                .andExpect(jsonPath("$.response").value("SOON"));

        // 任务不可催 → 422
        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"任务不可催\"}");
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + taskId + "/nudge")
                        .session(f.leader()).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 5. 通知中心与偏好 ----------

    @Test
    void notificationCenterAndPrefs() throws Exception {
        Fixture f = fixture("nt", "NTF", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        // SUBMIT 通知验收人（PO 为验收人）
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"待验收事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0
                        + "],\"acceptorId\":" + f.poId() + "}");
        progress(f.members().get(0), teamId, itemId, "{\"text\":\"做完待验收\"}");
        transition(f.members().get(0), teamId, itemId, "SUBMIT", version(f.members().get(0), teamId, itemId), null);
        assertThat(notificationsJson(f.po(), teamId)).contains("\"type\":\"SUBMIT\"");

        // 未读数 ≥ 1 → 全部已读 → 0
        MvcResult unread = mvc.perform(get("/api/v1/notifications/unread-count").session(f.po())
                        .param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        assertThat(((Number) JsonPath.read(unread.getResponse().getContentAsString(), "$.unread")).longValue()).isGreaterThanOrEqualTo(1);
        mvc.perform(post("/api/v1/notifications/read-all").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":" + teamId + "}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notifications/unread-count").session(f.po()).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(0));

        // 偏好：MENTION 可关，ASSIGNMENT 强制保持开启
        mvc.perform(patch("/api/v1/auth/notification-prefs").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"MENTION\":false,\"ASSIGNMENT\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MENTION").value(false))
                .andExpect(jsonPath("$.ASSIGNMENT").value(true));
        mvc.perform(get("/api/v1/auth/notification-prefs").session(f.po()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.MENTION").value(false));
    }

    // ---------- 6. 迭代全生命周期：开始冲突 / 快照 / 燃尽 / 关键日期 / 结束结转 ----------

    @Test
    void iterationLifecycleBurndownAndClose() throws Exception {
        Fixture f = fixture("it", "ITR", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);
        LocalDate today = LocalDate.now();

        MvcResult created = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"迭代一\",\"goal\":\"目标\",\"startDate\":\"" + today.minusDays(2)
                                + "\",\"endDate\":\"" + today.plusDays(11) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        long itId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"DEFECT\",\"title\":\"迭代内缺陷\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0
                        + "],\"iterationId\":" + itId + ",\"estimate\":5}");
        long doneId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"迭代内已完成\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0
                        + "],\"iterationId\":" + itId + ",\"estimate\":3}");
        progress(f.members().get(0), teamId, doneId, "{\"text\":\"完成\"}");
        transition(f.members().get(0), teamId, doneId, "SUBMIT", version(f.members().get(0), teamId, doneId), null);
        transition(f.leader(), teamId, doneId, "APPROVE", version(f.leader(), teamId, doneId), null);
        assertThat((String) JsonPath.read(getItemJson(f.leader(), teamId, doneId), "$.status")).isEqualTo("DONE");

        // 开始迭代 → ACTIVE + 首日快照；第二个迭代开始 → 409
        mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/start").session(f.leader()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        MvcResult created2 = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"迭代二\",\"startDate\":\"" + today.plusDays(12) + "\",\"endDate\":\"" + today.plusDays(25) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        long it2Id = ((Number) JsonPath.read(created2.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + it2Id + "/start").session(f.leader()).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACTIVE_ITERATION_EXISTS"));

        // 燃尽：至少一个快照点，总估算 5（已完成不计剩余）
        MvcResult burn = mvc.perform(get("/api/v1/teams/" + teamId + "/iterations/" + itId + "/burndown").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        String burnBody = burn.getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(burnBody, "$.points")).isNotEmpty();
        assertThat(((Number) JsonPath.read(burnBody, "$.totalEstimate")).doubleValue()).isEqualTo(5.0);

        // 关键日期：增 → 查 → 删
        MvcResult kd = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/key-dates")
                        .session(f.leader()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"封版\",\"date\":\"" + today.plusDays(9) + "\",\"category\":\"FREEZE\"}"))
                .andExpect(status().isCreated()).andReturn();
        long kdId = ((Number) JsonPath.read(kd.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(get("/api/v1/teams/" + teamId + "/iterations/" + itId + "/key-dates").session(f.leader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].category").value("FREEZE"));
        mvc.perform(delete("/api/v1/teams/" + teamId + "/key-dates/" + kdId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());

        // 结束：未完成事项结转到已存在的迭代二（缺省 NEXT），并新建改进项
        MvcResult closed = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations/" + itId + "/close")
                        .session(f.leader()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[],\"improvements\":[\"补齐自动化测试\"]}"))
                .andExpect(status().isOk()).andReturn();
        String closedBody = closed.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(closedBody, "$.iteration.status")).isEqualTo("FINISHED");
        assertThat(((Number) JsonPath.read(closedBody, "$.nextIterationId")).longValue()).isEqualTo(it2Id);
        assertThat(((Number) JsonPath.read(closedBody, "$.movedToNext")).intValue()).isEqualTo(1);
        assertThat((Object) JsonPath.read(closedBody, "$.reportId")).isNotNull();
        // 事项已结转到迭代二
        assertThat(((Number) JsonPath.read(getItemJson(f.leader(), teamId, itemId), "$.iterationId")).longValue()).isEqualTo(it2Id);
        // 改进项生成在迭代二且带「改进项」标签
        MvcResult nextItems = mvc.perform(get("/api/v1/teams/" + teamId + "/items").session(f.leader())
                        .param("iterationId", String.valueOf(it2Id)))
                .andExpect(status().isOk()).andReturn();
        List<String> titles = JsonPath.read(nextItems.getResponse().getContentAsString(), "$[*].title");
        assertThat(titles).contains("补齐自动化测试");
        // 迭代总结报告已生成
        MvcResult reports = mvc.perform(get("/api/v1/teams/" + teamId + "/reports").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        List<String> kinds = JsonPath.read(reports.getResponse().getContentAsString(), "$[*].kind");
        assertThat(kinds).contains("ITERATION_SUMMARY");
    }

    // ---------- 7. 自动提醒扫描 / 总览风险 / 站会 ----------

    @Test
    void reminderScanOverviewAndStandup() throws Exception {
        Fixture f = fixture("rm", "RMD", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"停滞事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        progress(f.members().get(0), teamId, itemId, "{\"text\":\"开始\"}");
        // 把最近进展时间拨回 6 天前（超过默认阈值 2 个工作日）
        var item = itemRepo.findById(itemId).orElseThrow();
        item.setLastProgressAt(Instant.now().minus(6, ChronoUnit.DAYS));
        itemRepo.save(item);

        // 普通成员不能触发扫描 → 403；组长扫描 → 生成提醒
        mvc.perform(post("/api/v1/teams/" + teamId + "/reminders/scan").session(f.members().get(0)).with(csrf()))
                .andExpect(status().isForbidden());
        MvcResult scan = mvc.perform(post("/api/v1/teams/" + teamId + "/reminders/scan").session(f.leader()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(((Number) JsonPath.read(scan.getResponse().getContentAsString(), "$.generated")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(notificationsJson(f.members().get(0), teamId)).contains("\"type\":\"REMINDER_STALE\"");
        // 当天重复扫描不重复生成（去重）
        MvcResult scan2 = mvc.perform(post("/api/v1/teams/" + teamId + "/reminders/scan").session(f.leader()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(((Number) JsonPath.read(scan2.getResponse().getContentAsString(), "$.generated")).intValue()).isEqualTo(0);

        // 总览：组长能看风险（含 STALE），成员不能
        MvcResult ov = mvc.perform(get("/api/v1/teams/" + teamId + "/overview").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(ov.getResponse().getContentAsString(), "$.canViewRisks")).isTrue();
        List<String> rules = JsonPath.read(ov.getResponse().getContentAsString(), "$.risks[*].rule");
        assertThat(rules).contains("STALE");
        // Q11（M4 对齐）：Overview 内部装配的事项也按当前用户填充 allowedActions（与主事项路径同口径），不得为空列表
        assertThat(ov.getResponse().getContentAsString()).contains("\"allowedActions\":[\"VIEW\"");
        MvcResult ovMember = mvc.perform(get("/api/v1/teams/" + teamId + "/overview").session(f.members().get(0)))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(ovMember.getResponse().getContentAsString(), "$.canViewRisks")).isFalse();

        // 站会：成员卡齐全；备注可存可读
        MvcResult standup = mvc.perform(get("/api/v1/teams/" + teamId + "/standup").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat((List<?>) JsonPath.read(standup.getResponse().getContentAsString(), "$.members")).hasSize(3);
        // Q11（M4 对齐）：Standup 内部装配的事项（今日在手）同样按当前用户填充 allowedActions
        assertThat(standup.getResponse().getContentAsString()).contains("\"allowedActions\":[\"VIEW\"");
        mvc.perform(put("/api/v1/teams/" + teamId + "/standup/note").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"" + LocalDate.now() + "\",\"content\":\"今日重点：推进停滞事项\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/teams/" + teamId + "/standup").session(f.leader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.note").value("今日重点：推进停滞事项"));
    }

    // ---------- 8. 报告生成/编辑/导出 + Excel 导出 ----------

    @Test
    void reportsAndExcelExport() throws Exception {
        Fixture f = fixture("rp", "RPT", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);
        createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"导出用事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");

        MvcResult gen = mvc.perform(post("/api/v1/teams/" + teamId + "/reports/generate").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"WEEKLY\"}"))
                .andExpect(status().isCreated()).andReturn();
        long reportId = ((Number) JsonPath.read(gen.getResponse().getContentAsString(), "$.id")).longValue();
        // M4 口径：AI 可用（mock 模式）时报告生成走 AI 叙述并校验一致，aiGenerated=true（M3 阶段恒 false 的断言已被 M4 取代）
        assertThat((Boolean) JsonPath.read(gen.getResponse().getContentAsString(), "$.aiGenerated")).isTrue();

        mvc.perform(post("/api/v1/teams/" + teamId + "/reports/generate").session(f.members().get(0)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"PERSONAL_WEEKLY\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").value(m0));

        // 组长可编辑；无关成员不可编辑他人的个人报告（此处用团队周报验证归属规则）
        mvc.perform(patch("/api/v1/teams/" + teamId + "/reports/" + reportId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"# 手工修订版\\n\\n补充说明\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.edited").value(true));

        MvcResult md = mvc.perform(get("/api/v1/teams/" + teamId + "/reports/" + reportId + "/export").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat(md.getResponse().getContentAsString()).contains("手工修订版");

        MvcResult xlsx = mvc.perform(get("/api/v1/teams/" + teamId + "/items/export").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        byte[] bytes = xlsx.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(100);
        assertThat(new String(bytes, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");
        assertThat(xlsx.getResponse().getContentType()).contains("spreadsheetml");
    }

    // ---------- 9. 进展四入口之「照常推进」+ 阻塞通知组长 ----------

    @Test
    void keepGoingProgressAndBlockNotification() throws Exception {
        Fixture f = fixture("kg", "KPG", 1);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"日常推进事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");

        // 照常推进：不填文字也可登记，kind=KEEP_GOING，默认文案
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/progress")
                        .session(f.members().get(0)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"KEEP_GOING\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("KEEP_GOING"))
                .andExpect(jsonPath("$.text").value("照常推进"));

        // 标记阻塞 → 组长收到 BLOCK 通知
        progress(f.members().get(0), teamId, itemId,
                "{\"text\":\"遇到问题\",\"blocked\":true,\"blockReason\":\"等待外部接口\"}");
        assertThat(notificationsJson(f.leader(), teamId)).contains("\"type\":\"BLOCK\"");
        assertThat((Boolean) JsonPath.read(getItemJson(f.leader(), teamId, itemId), "$.blocked")).isTrue();
    }

    // ---------- 10. PATCH 改负责人/标签 + inviteeIds 可见（待问 7d） ----------

    @Test
    void patchOwnersLabelsAndInviteeIds() throws Exception {
        Fixture f = fixture("pt", "PTC", 2);
        long teamId = f.team().teamId();
        long a = f.memberIds().get(0), b = f.memberIds().get(1);

        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"邀请制事项\",\"destination\":\"INVITE\",\"inviteeIds\":[" + a + "]}");
        List<Number> invitees = JsonPath.read(getItemJson(f.leader(), teamId, itemId), "$.inviteeIds");
        assertThat(invitees.stream().map(Number::longValue)).containsExactly(a);

        // A 认领后，组长用 PATCH 整体改负责人为 B，并换标签
        transition(f.members().get(0), teamId, itemId, "CLAIM", version(f.members().get(0), teamId, itemId), null);
        int v = version(f.leader(), teamId, itemId);
        MvcResult patched = mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v + ",\"ownerIds\":[" + b + "],\"labelNames\":[\"重点\",\"本月\"]}"))
                .andExpect(status().isOk()).andReturn();
        List<Number> owners = JsonPath.read(patched.getResponse().getContentAsString(), "$.ownerIds");
        assertThat(owners.stream().map(Number::longValue)).containsExactly(b);
        List<String> labels = JsonPath.read(patched.getResponse().getContentAsString(), "$.labels");
        assertThat(labels).containsExactlyInAnyOrder("重点", "本月");
    }

    // ---------- 11. 容量系数进入规划容量（待问 3） ----------

    @Test
    void planningCapacityUsesCoefficient() throws Exception {
        Fixture f = fixture("cp", "CAP", 1);
        long teamId = f.team().teamId();
        LocalDate today = LocalDate.now();

        mvc.perform(patch("/api/v1/teams/" + teamId).session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"capacityPointsPerWorkday\":2}}"))
                .andExpect(status().isOk());
        MvcResult created = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"容量迭代\",\"startDate\":\"" + today.minusDays(3)
                                + "\",\"endDate\":\"" + today.plusDays(10) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        long itId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult planning = mvc.perform(get("/api/v1/teams/" + teamId + "/iterations/" + itId + "/planning").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        List<Object> caps = JsonPath.read(planning.getResponse().getContentAsString(), "$.capacities");
        assertThat(caps).isNotEmpty();
        Number remainingDays = JsonPath.read(planning.getResponse().getContentAsString(), "$.capacities[0].remainingDays");
        Number capacityPoints = JsonPath.read(planning.getResponse().getContentAsString(), "$.capacities[0].capacityPoints");
        assertThat(remainingDays.intValue()).isGreaterThan(0);
        assertThat(capacityPoints.doubleValue()).isEqualTo(remainingDays.intValue() * 2.0);
    }
}
