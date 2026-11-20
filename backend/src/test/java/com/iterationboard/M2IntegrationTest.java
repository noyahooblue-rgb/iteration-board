package com.iterationboard;

import com.iterationboard.domain.Iteration;
import com.iterationboard.repo.IterationRepo;
import com.iterationboard.repo.NotificationRepo;
import com.iterationboard.repo.TeamMemberRepo;
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
 * M2 集成测试（修订一）：婉拒、故事/任务权限、任务推算、批量下发、共同负责、
 * 双组隔离、回收站、搜索、待办池/排序/规划/保存筛选。MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class M2IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepo notificationRepo;
    @Autowired TeamMemberRepo memberRepo;
    @Autowired IterationRepo iterationRepo;
    @Autowired com.iterationboard.repo.TeamRepo teamRepo;

    // ---------- helpers ----------

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

    private MvcResult createItemRaw(MockHttpSession s, long teamId, String json) throws Exception {
        return mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private long createItem(MockHttpSession s, long teamId, String json) throws Exception {
        MvcResult r = createItemRaw(s, teamId, json);
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

    private void progress(MockHttpSession s, long teamId, long itemId, String text) throws Exception {
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/progress").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(status().isCreated());
    }

    /** 把一个事项从负责人手里推到 DONE（进展→提交→PO/创建人验收通过）。 */
    private void completeItem(MockHttpSession owner, MockHttpSession approver, long teamId, long itemId) throws Exception {
        progress(owner, teamId, itemId, "已完成全部工作");
        MvcResult sub = transition(owner, teamId, itemId, "SUBMIT", version(owner, teamId, itemId), null);
        assertThat(sub.getResponse().getStatus()).isEqualTo(200);
        String st = JsonPath.read(sub.getResponse().getContentAsString(), "$.status");
        if ("IN_REVIEW".equals(st)) {
            MvcResult ap = transition(approver, teamId, itemId, "APPROVE", version(approver, teamId, itemId), null);
            assertThat(ap.getResponse().getStatus()).isEqualTo(200);
        }
        assertThat((String) JsonPath.read(getItemJson(owner, teamId, itemId), "$.status")).isEqualTo("DONE");
    }

    /** 建组 + PO + 若干成员的脚手架。 */
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

    // ---------- 1. 婉拒 DECLINE ----------

    @Test
    void declineRules() throws Exception {
        Fixture f = fixture("dc", "DCL", 2);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"被指派事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        String created = getItemJson(f.leader(), teamId, itemId);
        assertThat((Boolean) JsonPath.read(created, "$.assignedDirectly")).isTrue();

        // 非被指派人婉拒 → 403
        MvcResult byOther = transition(f.members().get(1), teamId, itemId, "DECLINE",
                version(f.members().get(1), teamId, itemId), "\"reason\":\"不想做\"");
        assertThat(byOther.getResponse().getStatus()).isEqualTo(403);
        // 被指派人无原因婉拒 → 400 REASON_REQUIRED
        MvcResult noReason = transition(f.members().get(0), teamId, itemId, "DECLINE",
                version(f.members().get(0), teamId, itemId), null);
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(noReason.getResponse().getContentAsString(), "$.code")).isEqualTo("REASON_REQUIRED");
        // 带原因婉拒成功 → UNCLAIMED、负责人清空、通知创建人
        long creatorId = myId(f.leader());
        MvcResult ok = transition(f.members().get(0), teamId, itemId, "DECLINE",
                version(f.members().get(0), teamId, itemId), "\"reason\":\"手头排满了\"");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("UNCLAIMED");
        assertThat(((Number) JsonPath.read(body, "$.ownerIds.length()")).intValue()).isEqualTo(0);
        assertThat(notificationRepo.countByRecipientIdAndTypeAndItemId(creatorId, "DECLINE", itemId)).isEqualTo(1);

        // 登记进展后婉拒被拒：新事项指派→登记进展（自动开始）→DECLINE 422
        long item2 = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"已开始事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        progress(f.members().get(0), teamId, item2, "已经动手了");
        MvcResult afterProgress = transition(f.members().get(0), teamId, item2, "DECLINE",
                version(f.members().get(0), teamId, item2), "\"reason\":\"不想做了\"");
        assertThat(afterProgress.getResponse().getStatus()).isEqualTo(422);

        // 认领（非直接指派）的事项不可婉拒 → 403
        long item3 = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"认领池事项\",\"destination\":\"POOL\"}");
        transition(f.members().get(0), teamId, item3, "CLAIM", version(f.members().get(0), teamId, item3), null);
        MvcResult claimedDecline = transition(f.members().get(0), teamId, item3, "DECLINE",
                version(f.members().get(0), teamId, item3), "\"reason\":\"反悔\"");
        assertThat(claimedDecline.getResponse().getStatus()).isEqualTo(403);
    }

    // ---------- 2. 故事/任务创建权限与父子校验 ----------

    @Test
    void storyAndTaskCreationPermissions() throws Exception {
        Fixture f = fixture("sp", "STP", 1);
        long teamId = f.team().teamId();

        // 任务：成员 403、PO 201；组长 1.1.0 起也可创建（修订四 P06：组长和 PO 都可创建任务/故事，原「组长 403」断言随规则变更调整）
        assertThat(createItemRaw(f.members().get(0), teamId,
                "{\"type\":\"TASK\",\"title\":\"成员建任务\",\"destination\":\"POOL\"}").getResponse().getStatus()).isEqualTo(403);
        assertThat(createItemRaw(f.leader(), teamId,
                "{\"type\":\"TASK\",\"title\":\"组长建任务\",\"destination\":\"POOL\"}").getResponse().getStatus()).isEqualTo(201);
        long taskId = createItem(f.po(), teamId,
                "{\"type\":\"TASK\",\"title\":\"PO的任务\",\"destination\":\"POOL\"}");
        String taskJson = getItemJson(f.po(), teamId, taskId);
        assertThat((String) JsonPath.read(taskJson, "$.taskStatus")).isEqualTo("NOT_STARTED");
        assertThat(((Number) JsonPath.read(taskJson, "$.poId")).longValue()).isEqualTo(f.poId());

        // 故事：非 PO 创建 403
        assertThat(createItemRaw(f.members().get(0), teamId,
                "{\"type\":\"STORY\",\"title\":\"成员建故事\",\"destination\":\"POOL\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"DEV\"}").getResponse().getStatus()).isEqualTo(403);
        // PO 建故事无父任务 → 400
        assertThat(createItemRaw(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"无父故事\",\"destination\":\"POOL\",\"storyCategory\":\"DEV\"}")
                .getResponse().getStatus()).isEqualTo(400);
        // PO 建故事父为非任务类型 → 400
        long adhocId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"独立事项\",\"destination\":\"POOL\"}");
        assertThat(createItemRaw(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"错父故事\",\"destination\":\"POOL\",\"parentId\":" + adhocId
                        + ",\"storyCategory\":\"DEV\"}").getResponse().getStatus()).isEqualTo(400);
        // PO 建故事无类别 → 400；带类别 → 201
        assertThat(createItemRaw(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"无类别故事\",\"destination\":\"POOL\",\"parentId\":" + taskId + "}")
                .getResponse().getStatus()).isEqualTo(400);
        long storyId = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"正规故事\",\"destination\":\"POOL\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"DEV\"}");
        String storyJson = getItemJson(f.po(), teamId, storyId);
        assertThat((String) JsonPath.read(storyJson, "$.storyCategory")).isEqualTo("DEV");
    }

    // ---------- 3. 任务推算三态 + 待验收通知 PO + PO 通过 ----------

    @Test
    void taskDerivationLifecycle() throws Exception {
        Fixture f = fixture("td", "TDV", 1);
        long teamId = f.team().teamId();
        MockHttpSession member = f.members().get(0);

        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"推算任务\",\"destination\":\"POOL\"}");
        long s1 = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"故事一\",\"destination\":\"POOL\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"DEV\",\"estimate\":3}");
        long s2 = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"故事二\",\"destination\":\"POOL\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"TEST\",\"estimate\":1}");

        String t0 = getItemJson(f.po(), teamId, taskId);
        assertThat((String) JsonPath.read(t0, "$.taskStatus")).isEqualTo("NOT_STARTED");
        assertThat(((Number) JsonPath.read(t0, "$.progressPercent")).intValue()).isEqualTo(0);

        // 完成故事一（3 点）→ 任务进行中，进度按故事点加权 3/4=75
        transition(member, teamId, s1, "CLAIM", version(member, teamId, s1), null);
        completeItem(member, f.po(), teamId, s1);
        String t1 = getItemJson(f.po(), teamId, taskId);
        assertThat((String) JsonPath.read(t1, "$.taskStatus")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) JsonPath.read(t1, "$.progressPercent")).intValue()).isEqualTo(75);

        // 完成故事二 → 全部完成 → 待+ 通知 PO
        transition(member, teamId, s2, "CLAIM", version(member, teamId, s2), null);
        completeItem(member, f.po(), teamId, s2);
        String t2 = getItemJson(f.po(), teamId, taskId);
        assertThat((String) JsonPath.read(t2, "$.taskStatus")).isEqualTo("PENDING_ACCEPTANCE");
        assertThat(((Number) JsonPath.read(t2, "$.progressPercent")).intValue()).isEqualTo(100);
        assertThat(notificationRepo.countByRecipientIdAndTypeAndItemId(f.poId(), "TASK_PENDING_ACCEPTANCE", taskId))
                .isGreaterThanOrEqualTo(1);

        // 非 PO 通过被拒；PO 通过 → 已完成
        MvcResult byMember = transition(member, teamId, taskId, "APPROVE", version(member, teamId, taskId), null);
        assertThat(byMember.getResponse().getStatus()).isEqualTo(403);
        MvcResult byPo = transition(f.po(), teamId, taskId, "APPROVE", version(f.po(), teamId, taskId), null);
        assertThat(byPo.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(byPo.getResponse().getContentAsString(), "$.taskStatus")).isEqualTo("DONE");
    }

    // ---------- 4. 批量下发：7 人汇总、催未完成、整批取消 ----------

    @Test
    void batchDispatchSummaryNudgeCancel() throws Exception {
        Fixture f = fixture("bt", "BAT", 7);
        long teamId = f.team().teamId();
        String memberIdsJson = f.memberIds().toString();

        MvcResult created = mvc.perform(post("/api/v1/teams/" + teamId + "/dispatch-batches").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ADHOC\",\"title\":\"全员培训材料阅读\",\"memberIds\":" + memberIdsJson + "}"))
                .andExpect(status().isCreated()).andReturn();
        String cb = created.getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(cb, "$.batchId")).longValue();
        assertThat(((Number) JsonPath.read(cb, "$.total")).intValue()).isEqualTo(7);
        assertThat(((Number) JsonPath.read(cb, "$.doneCount")).intValue()).isEqualTo(0);
        assertThat(((Number) JsonPath.read(cb, "$.members.length()")).intValue()).isEqualTo(7);

        // 成员 0 完成自己那份（验收人默认=批次创建人 PO）
        long item0 = ((Number) JsonPath.read(cb, "$.members[0].itemId")).longValue();
        completeItem(f.members().get(0), f.po(), teamId, item0);
        long leaveItem = ((Number) JsonPath.read(cb, "$.members[1].itemId")).longValue();

        // 成员 1 设为请假（覆盖今天）
        var tm = memberRepo.findByTeamIdAndUserId(teamId, f.memberIds().get(1)).orElseThrow();
        tm.setMemberStatus("LEAVE");
        tm.setStatusStart(LocalDate.now().minusDays(1));
        tm.setStatusEnd(LocalDate.now().plusDays(1));
        memberRepo.save(tm);

        // 一键催未完成：5 人被催、请假成员改记组长
        MvcResult nudge1 = mvc.perform(post("/api/v1/teams/" + teamId + "/dispatch-batches/" + batchId + "/nudge")
                        .session(f.po()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String n1 = nudge1.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(n1, "$.nudgedItemIds.length()")).intValue()).isEqualTo(5);
        assertThat(((Number) JsonPath.read(n1, "$.redirectedItemIds.length()")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(n1, "$.redirectedItemIds[0]")).longValue()).isEqualTo(leaveItem);
        // 同一天第二次催：每天一次规则全挡
        MvcResult nudge2 = mvc.perform(post("/api/v1/teams/" + teamId + "/dispatch-batches/" + batchId + "/nudge")
                        .session(f.po()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String n2 = nudge2.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(n2, "$.nudgedItemIds.length()")).intValue()).isEqualTo(0);
        assertThat(((Number) JsonPath.read(n2, "$.skippedItemIds.length()")).intValue()).isEqualTo(6);

        // 汇总：1/7 完成
        MvcResult summary = mvc.perform(get("/api/v1/teams/" + teamId + "/dispatch-batches/" + batchId).session(f.po()))
                .andExpect(status().isOk()).andReturn();
        String sb = summary.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(sb, "$.doneCount")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(sb, "$.total")).intValue()).isEqualTo(7);

        // 整批取消无原因 → 400；带原因 → 已完成保留、其余取消
        mvc.perform(post("/api/v1/teams/" + teamId + "/dispatch-batches/" + batchId + "/cancel").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        MvcResult cancelled = mvc.perform(post("/api/v1/teams/" + teamId + "/dispatch-batches/" + batchId + "/cancel")
                        .session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"培训改期\"}"))
                .andExpect(status().isOk()).andReturn();
        String kb = cancelled.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(kb, "$.doneCount")).intValue()).isEqualTo(1);
        assertThat((String) JsonPath.read(kb, "$.members[0].status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(kb, "$.members[1].status")).isEqualTo("CANCELLED");
    }

    // ---------- 5. 共同负责 join ----------

    @Test
    void coOwnerJoinBothCanProgressAndSubmit() throws Exception {
        Fixture f = fixture("co", "COW", 2);
        long teamId = f.team().teamId();
        long m0 = f.memberIds().get(0);

        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"协作事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + m0 + "]}");
        // 成员 1 加入共同负责
        MvcResult joined = mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/join")
                        .session(f.members().get(1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version(f.members().get(1), teamId, itemId) + "}"))
                .andExpect(status().isOk()).andReturn();
        String jb = joined.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(jb, "$.ownerIds.length()")).intValue()).isEqualTo(2);
        // 重复加入 → Q13（M4 待问结论）：动作本身幂等安全，返回当前状态 200 而非 409
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/join")
                        .session(f.members().get(1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version(f.members().get(1), teamId, itemId) + "}"))
                .andExpect(status().isOk());
        // 双方均可登记进展
        progress(f.members().get(1), teamId, itemId, "加入者登记进展");
        progress(f.members().get(0), teamId, itemId, "原负责人登记进展");
        // 任一共同负责人可提交验收（由加入者提交，验收人=创建人组长）
        MvcResult sub = transition(f.members().get(1), teamId, itemId, "SUBMIT",
                version(f.members().get(1), teamId, itemId), null);
        assertThat(sub.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(sub.getResponse().getContentAsString(), "$.status")).isEqualTo("IN_REVIEW");
    }

    // ---------- 6. 双组隔离与切换 ----------

    @Test
    void dualTeamIsolationAndSwitching() throws Exception {
        MockHttpSession user = register("dualuser", "双组用户");
        TeamCtx a = createTeam(user, "甲组", "DLA");
        MockHttpSession other = register("dualother", "乙组组长");
        TeamCtx b = createTeam(other, "乙组", "DLB");
        join(user, b.invite());

        long itemA = createItem(user, a.teamId(), "{\"type\":\"ADHOC\",\"title\":\"甲组独有\",\"destination\":\"POOL\"}");
        long itemB = createItem(user, b.teamId(), "{\"type\":\"ADHOC\",\"title\":\"乙组独有\",\"destination\":\"POOL\"}");

        // 我加入的小组有 2 个
        mvc.perform(get("/api/v1/teams/mine").session(user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        // 各组列表只含本组事项
        MvcResult listA = mvc.perform(get("/api/v1/teams/" + a.teamId() + "/items").session(user))
                .andExpect(status().isOk()).andReturn();
        String la = listA.getResponse().getContentAsString();
        assertThat(la).contains("甲组独有").doesNotContain("乙组独有");
        MvcResult listB = mvc.perform(get("/api/v1/teams/" + b.teamId() + "/items").session(user))
                .andExpect(status().isOk()).andReturn();
        assertThat(listB.getResponse().getContentAsString()).contains("乙组独有").doesNotContain("甲组独有");
        // 用甲组路径取乙组事项 → 404（隔离）
        mvc.perform(get("/api/v1/teams/" + a.teamId() + "/items/" + itemB).session(user)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/teams/" + b.teamId() + "/items/" + itemA).session(user)).andExpect(status().isNotFound());
    }

    // ---------- 7. 删除进回收站可恢复 ----------

    @Test
    void softDeleteTrashAndRestore() throws Exception {
        Fixture f = fixture("tr", "TRS", 0);
        long teamId = f.team().teamId();
        long itemId = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"待删事项\",\"destination\":\"POOL\"}");
        mvc.perform(delete("/api/v1/teams/" + teamId + "/items/" + itemId).session(f.leader()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(f.leader()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/teams/" + teamId + "/trash").session(f.leader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("待删事项"));
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/restore").session(f.leader()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("待删事项"));
        mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(f.leader()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/teams/" + teamId + "/trash").session(f.leader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- 8. 搜索：标题与评论命中 ----------

    @Test
    void searchByTitleAndComment() throws Exception {
        Fixture f = fixture("se", "SEA", 0);
        long teamId = f.team().teamId();
        long byTitle = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"报表导出优化\",\"destination\":\"POOL\"}");
        long byComment = createItem(f.leader(), teamId,
                "{\"type\":\"ADHOC\",\"title\":\"无关标题\",\"destination\":\"POOL\"}");
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + byComment + "/comments").session(f.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"这个和报表导出有关\"}"))
                .andExpect(status().isCreated());

        MvcResult r = mvc.perform(get("/api/v1/teams/" + teamId + "/search").param("q", "报表导出").session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        List<Object> ids = JsonPath.read(body, "$[*].id");
        assertThat(ids.stream().map(o -> ((Number) o).longValue())).containsExactlyInAnyOrder(byTitle, byComment);
        // 编号命中
        String code = JsonPath.read(getItemJson(f.leader(), teamId, byTitle), "$.code");
        MvcResult r2 = mvc.perform(get("/api/v1/teams/" + teamId + "/search").param("q", code).session(f.leader()))
                .andExpect(status().isOk()).andReturn();
        assertThat(((Number) JsonPath.read(r2.getResponse().getContentAsString(), "$.length()")).intValue()).isEqualTo(1);
    }

    // ---------- 9. 待办池、故事排序、迭代规划、保存筛选 ----------

    @Test
    void backlogReorderPlanningAndSavedFilters() throws Exception {
        Fixture f = fixture("pl", "PLN", 2);
        long teamId = f.team().teamId();

        // 迭代（无创建接口属 M3，此处直接落库作为规划查询的夹具）
        Iteration it = new Iteration();
        it.setTeam(teamRepo.findById(teamId).orElseThrow());
        it.setName("迭代一");
        it.setStartDate(LocalDate.of(2026, 11, 16)); // 周一
        it.setEndDate(LocalDate.of(2026, 11, 27));   // 周五，共 10 个工作日
        it.setStatus("ACTIVE");
        iterationRepo.save(it);

        long taskId = createItem(f.po(), teamId, "{\"type\":\"TASK\",\"title\":\"规划任务\",\"destination\":\"POOL\"}");
        long s1 = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"故事甲\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + f.memberIds().get(0)
                        + "],\"parentId\":" + taskId + ",\"storyCategory\":\"DEV\",\"estimate\":4,\"iterationId\":" + it.getId() + "}");
        long s2 = createItem(f.po(), teamId,
                "{\"type\":\"STORY\",\"title\":\"故事乙\",\"destination\":\"POOL\",\"parentId\":" + taskId
                        + ",\"storyCategory\":\"TEST\",\"estimate\":2,\"iterationId\":" + it.getId() + "}");

        // 待办池：任务 + 故事概要
        MvcResult backlog = mvc.perform(get("/api/v1/teams/" + teamId + "/backlog").session(f.po()))
                .andExpect(status().isOk()).andReturn();
        String bb = backlog.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(bb, "$.length()")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(bb, "$[0].storyCount")).intValue()).isEqualTo(2);

        // 故事排序：乙排到甲前面
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + taskId + "/stories/reorder").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[" + s2 + "," + s1 + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(s2))
                .andExpect(jsonPath("$[1].id").value(s1));

        // 共同负责后规划负载均分：故事甲 4 点由两人各担 2 点
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + s1 + "/join").session(f.members().get(1)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version(f.members().get(1), teamId, s1) + "}"))
                .andExpect(status().isOk());
        MvcResult planning = mvc.perform(get("/api/v1/teams/" + teamId + "/iterations/" + it.getId() + "/planning")
                        .session(f.po()))
                .andExpect(status().isOk()).andReturn();
        String pb = planning.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(pb, "$.groups.length()")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(pb, "$.groups[0].items.length()")).intValue()).isEqualTo(2);
        List<Object> loads = JsonPath.read(pb, "$.loads");
        assertThat(loads).hasSize(2);
        for (Object load : loads) {
            assertThat(((Number) JsonPath.read(load, "$.points")).doubleValue()).isEqualTo(2.0);
        }
        // 容量：成员 0 可用天数 = 10 个工作日
        List<Object> caps = JsonPath.read(pb, "$.capacities");
        boolean foundCap = caps.stream().anyMatch(c ->
                ((Number) JsonPath.read(c, "$.userId")).longValue() == f.memberIds().get(0)
                        && ((Number) JsonPath.read(c, "$.availableDays")).intValue() == 10);
        assertThat(foundCap).isTrue();

        // 保存筛选：增 → 查 → 删
        MvcResult sf = mvc.perform(post("/api/v1/teams/" + teamId + "/saved-filters").session(f.po()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"只看故事\",\"criteria\":{\"type\":\"STORY\"}}"))
                .andExpect(status().isCreated()).andReturn();
        long filterId = ((Number) JsonPath.read(sf.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(get("/api/v1/teams/" + teamId + "/saved-filters").session(f.po()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].criteria.type").value("STORY"));
        mvc.perform(delete("/api/v1/teams/" + teamId + "/saved-filters/" + filterId).session(f.po()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/teams/" + teamId + "/saved-filters").session(f.po()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
