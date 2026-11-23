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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M1 第一阶段集成测试：MockMvc + 真实 PostgreSQL（iteration_board 库，事务回滚隔离）。
 * 覆盖任务书 8 项验收场景。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class M1IntegrationTest {

    @Autowired MockMvc mvc;

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
        assertThat(code).hasSize(4);
        return captchaId + "|" + code;
    }

    private MockHttpSession register(String loginName, String displayName, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String[] c = captchaCode(session).split("\\|");
        mvc.perform(post("/api/v1/auth/register").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"displayName\":\"" + displayName
                                + "\",\"password\":\"" + password + "\",\"captchaId\":\"" + c[0]
                                + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        return session;
    }

    private MvcResult login(MockHttpSession session, String loginName, String password, boolean withCaptcha) throws Exception {
        String captchaPart = "";
        if (withCaptcha) {
            String[] c = captchaCode(session).split("\\|");
            captchaPart = ",\"captchaId\":\"" + c[0] + "\",\"captchaCode\":\"" + c[1] + "\"";
        }
        return mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"password\":\"" + password + "\"" + captchaPart + "}"))
                .andReturn();
    }

    private long myId(MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private MvcResult createTeam(MockHttpSession session, String name, String code, boolean joinApproval) throws Exception {
        return mvc.perform(post("/api/v1/teams").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"joinApproval\":" + joinApproval + "}"))
                .andExpect(status().isCreated()).andReturn();
    }

    private long createItem(MockHttpSession session, long teamId, String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String getItemJson(MockHttpSession session, long teamId, long itemId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(session))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    private int itemVersion(MockHttpSession session, long teamId, long itemId) throws Exception {
        return ((Number) JsonPath.read(getItemJson(session, teamId, itemId), "$.version")).intValue();
    }

    private MvcResult transition(MockHttpSession session, long teamId, long itemId, String action, int version, String extra) throws Exception {
        return mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/transitions").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"" + action + "\",\"version\":" + version + (extra == null ? "" : "," + extra) + "}"))
                .andReturn();
    }

    // ---------- 0. 冒烟：meta 与 OpenAPI 文档端点 ----------

    @Test
    void metaAndOpenApiDocsAvailable() throws Exception {
        mvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationOpen").value(true))
                .andExpect(jsonPath("$.demoMode").value(false));
        // rc2 安全裁定（hsa43amn）：接口文档生产默认关闭且仅平台管理员可访问，未登录一律 401
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
        // 契约原文静态文件同口径
        mvc.perform(get("/openapi.yaml")).andExpect(status().isUnauthorized());
        // 未登录访问受保护接口 → 401 统一错误格式
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---------- 1. 注册→登录→改密→新密码登录；第一个账号 platformAdmin ----------
    // 1.1.0 修订（P01/q3tbdxut）：取消「第一个注册自动成为平台管理员」，自助注册永不授予平台管理员，
    // 故此处断言由 isTrue 改为 isFalse（规则变更，见 V110IntegrationTest 的引导创建 admin 覆盖）。

    @Test
    void registerLoginChangePassword_firstUserIsNotPlatformAdmin() throws Exception {
        MockHttpSession s = register("alice", "爱丽丝", "passw0rd1!");
        MvcResult me = mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk()).andReturn();
        String body = me.getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(body, "$.platformAdmin")).isFalse();
        assertThat((String) JsonPath.read(body, "$.loginName")).isEqualTo("alice");

        mvc.perform(post("/api/v1/auth/change-password").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"passw0rd1!\",\"newPassword\":\"newpass123!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").session(s).with(csrf())).andExpect(status().isNoContent());

        MockHttpSession s2 = new MockHttpSession();
        MvcResult oldLogin = login(s2, "alice", "passw0rd1!", false);
        assertThat(oldLogin.getResponse().getStatus()).isEqualTo(401);
        MvcResult newLogin = login(s2, "alice", "newpass123!", false);
        assertThat(newLogin.getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- 2. 弱密码 400；重名 409 ----------

    @Test
    void weakPasswordRejected_and_duplicateLoginNameConflict() throws Exception {
        MockHttpSession s = new MockHttpSession();
        String[] c = captchaCode(s).split("\\|");
        mvc.perform(post("/api/v1/auth/register").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"weakling\",\"displayName\":\"弱密码\",\"password\":\"short1\",\"captchaId\":\"" + c[0] + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));

        register("bob", "鲍勃", "passw0rd1!");
        MockHttpSession s2 = new MockHttpSession();
        String[] c2 = captchaCode(s2).split("\\|");
        mvc.perform(post("/api/v1/auth/register").session(s2).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"bob\",\"displayName\":\"鲍勃二号\",\"password\":\"passw0rd1!\",\"captchaId\":\"" + c2[0] + "\",\"captchaCode\":\"" + c2[1] + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOGIN_NAME_TAKEN"));
    }

    // ---------- 3. 登录失败 3 次后要验证码；5 次 423 锁定 ----------

    @Test
    void loginCaptchaAfter3Failures_and_lockAfter5() throws Exception {
        register("carol", "卡罗尔", "passw0rd1!");
        MockHttpSession s = new MockHttpSession();
        for (int i = 0; i < 3; i++) {
            MvcResult r = login(s, "carol", "wrongpass1", false);
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
        }
        // 第 4 次：无验证码 → CAPTCHA_REQUIRED
        MvcResult r4 = login(s, "carol", "wrongpass1", false);
        assertThat(r4.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(r4.getResponse().getContentAsString(), "$.code")).isEqualTo("CAPTCHA_REQUIRED");
        // 带验证码错密码 → 第 4 次失败 401
        MvcResult r5 = login(s, "carol", "wrongpass1", true);
        assertThat(r5.getResponse().getStatus()).isEqualTo(401);
        // 带验证码错密码 → 第 5 次失败 → 423 锁定
        MvcResult r6 = login(s, "carol", "wrongpass1", true);
        assertThat(r6.getResponse().getStatus()).isEqualTo(423);
        String body6 = r6.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body6, "$.code")).isEqualTo("ACCOUNT_LOCKED");
        assertThat((String) JsonPath.read(body6, "$.detail.lockedUntil")).isNotBlank();
        // 锁定期间正确密码也被拒
        MvcResult r7 = login(s, "carol", "passw0rd1!", true);
        assertThat(r7.getResponse().getStatus()).isEqualTo(423);
    }

    // ---------- 4. 建组邀请码→申请→审批→成员列表；审批前看不到事项 ----------

    @Test
    void teamJoinApprovalFlow() throws Exception {
        MockHttpSession leader = register("leader1", "组长一", "passw0rd1!");
        MvcResult team = createTeam(leader, "星辰组", "SKY", true);
        String teamBody = team.getResponse().getContentAsString();
        long teamId = ((Number) JsonPath.read(teamBody, "$.id")).longValue();
        String invite = JsonPath.read(teamBody, "$.inviteCode");
        assertThat(invite).hasSize(8);
        long itemId = createItem(leader, teamId,
                "{\"type\":\"ADHOC\",\"title\":\"组内事项\",\"destination\":\"POOL\"}");

        MockHttpSession member = register("member1", "成员一", "passw0rd1!");
        // 预览
        MvcResult preview = mvc.perform(get("/api/v1/teams/by-invite/" + invite).session(member))
                .andExpect(status().isOk()).andReturn();
        String pv = preview.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(pv, "$.leaderName")).isEqualTo("组长一");
        assertThat((Boolean) JsonPath.read(pv, "$.alreadyMember")).isFalse();
        // 申请加入（需审批）
        mvc.perform(post("/api/v1/teams/join").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joined").value(false));
        // 重复申请 409
        mvc.perform(post("/api/v1/teams/join").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isConflict());
        // 审批前看不到组内事项与成员
        mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(member)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/teams/" + teamId + "/members").session(member)).andExpect(status().isNotFound());
        // 组长审批
        MvcResult reqs = mvc.perform(get("/api/v1/teams/" + teamId + "/join-requests").session(leader))
                .andExpect(status().isOk()).andReturn();
        long requestId = ((Number) JsonPath.read(reqs.getResponse().getContentAsString(), "$[0].id")).longValue();
        mvc.perform(post("/api/v1/teams/" + teamId + "/join-requests/" + requestId + "/approve").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
        // 审批后可见成员列表与事项
        mvc.perform(get("/api/v1/teams/" + teamId + "/members").session(member))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(member)).andExpect(status().isOk());
    }

    // ---------- 5. 跨组隔离 404 ----------

    @Test
    void crossTeamIsolation() throws Exception {
        MockHttpSession a = register("usera", "甲用户", "passw0rd1!");
        MvcResult teamA = createTeam(a, "甲组", "AAA", false);
        long teamAId = ((Number) JsonPath.read(teamA.getResponse().getContentAsString(), "$.id")).longValue();
        long itemA = createItem(a, teamAId, "{\"type\":\"ADHOC\",\"title\":\"甲组事项\",\"destination\":\"POOL\"}");

        MockHttpSession b = register("userb", "乙用户", "passw0rd1!");
        createTeam(b, "乙组", "BBB", false);

        mvc.perform(get("/api/v1/teams/" + teamAId + "/items/" + itemA).session(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/teams/" + teamAId + "/items").session(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/teams/" + teamAId + "/members").session(b)).andExpect(status().isNotFound());
    }

    // ---------- 6. 权限拒绝 403 ----------

    @Test
    void permissionDenied() throws Exception {
        MockHttpSession leader = register("leader2", "组长二", "passw0rd1!");
        MvcResult team = createTeam(leader, "权限组", "PER", false);
        String tb = team.getResponse().getContentAsString();
        long teamId = ((Number) JsonPath.read(tb, "$.id")).longValue();
        String invite = JsonPath.read(tb, "$.inviteCode");
        long leaderId = myId(leader);

        MockHttpSession member = register("member2", "成员二", "passw0rd1!");
        mvc.perform(post("/api/v1/teams/join").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(true));

        // 普通成员调角色分配 → 403
        mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + leaderId + "/role").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
        // 组长把自己降级（最后一名组长）→ 400 LAST_LEADER
        mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + leaderId + "/role").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_LEADER"));

        // 成员 PATCH 他人创建且不负责的事项 → 403
        long itemId = createItem(leader, teamId,
                "{\"type\":\"ADHOC\",\"title\":\"组长事项\",\"destination\":\"ASSIGN\",\"assigneeId\":" + leaderId + "}");
        int v = itemVersion(leader, teamId, itemId);
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId).session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v + ",\"title\":\"篡改标题\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 7. 流转全链 + 非法流转 422 + REJECT 无原因 400 ----------

    @Test
    void transitionFullChain() throws Exception {
        MockHttpSession leader = register("leader3", "组长三", "passw0rd1!");
        MvcResult team = createTeam(leader, "流转组", "FLW", false);
        String tb = team.getResponse().getContentAsString();
        long teamId = ((Number) JsonPath.read(tb, "$.id")).longValue();
        String invite = JsonPath.read(tb, "$.inviteCode");

        MockHttpSession member = register("member3", "成员三", "passw0rd1!");
        long memberId = myId(member);
        mvc.perform(post("/api/v1/teams/join").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk());

        long itemId = createItem(leader, teamId, "{\"type\":\"ADHOC\",\"title\":\"全链事项\",\"destination\":\"POOL\"}");
        // CLAIM
        MvcResult claim = transition(member, teamId, itemId, "CLAIM", itemVersion(member, teamId, itemId), null);
        assertThat(claim.getResponse().getStatus()).isEqualTo(200);
        String claimed = claim.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(claimed, "$.status")).isEqualTo("NOT_STARTED");
        assertThat(((Number) JsonPath.read(claimed, "$.ownerIds[0]")).longValue()).isEqualTo(memberId);

        // 登记进展自动 START
        mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + itemId + "/progress").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"开始干活\",\"percent\":30}"))
                .andExpect(status().isCreated());
        String afterProgress = getItemJson(member, teamId, itemId);
        assertThat((String) JsonPath.read(afterProgress, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) JsonPath.read(afterProgress, "$.progressPercent")).intValue()).isEqualTo(30);
        // activities 含 START
        MvcResult acts = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId + "/activities").session(member))
                .andExpect(status().isOk()).andReturn();
        assertThat(acts.getResponse().getContentAsString()).contains("\"START\"");

        // 非法流转：IN_PROGRESS 直接 APPROVE → 422
        MvcResult illegal = transition(leader, teamId, itemId, "APPROVE", itemVersion(leader, teamId, itemId), null);
        assertThat(illegal.getResponse().getStatus()).isEqualTo(422);
        assertThat((String) JsonPath.read(illegal.getResponse().getContentAsString(), "$.code")).isEqualTo("ILLEGAL_TRANSITION");

        // SUBMIT → IN_REVIEW
        MvcResult submit = transition(member, teamId, itemId, "SUBMIT", itemVersion(member, teamId, itemId), null);
        assertThat(submit.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(submit.getResponse().getContentAsString(), "$.status")).isEqualTo("IN_REVIEW");

        // REJECT 无原因 → 400
        MvcResult rejectNoReason = transition(leader, teamId, itemId, "REJECT", itemVersion(leader, teamId, itemId), null);
        assertThat(rejectNoReason.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(rejectNoReason.getResponse().getContentAsString(), "$.code")).isEqualTo("REASON_REQUIRED");
        // REJECT 带原因 → IN_PROGRESS
        MvcResult reject = transition(leader, teamId, itemId, "REJECT", itemVersion(leader, teamId, itemId), "\"reason\":\"还差边界处理\"");
        assertThat(reject.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(reject.getResponse().getContentAsString(), "$.status")).isEqualTo("IN_PROGRESS");

        // 再次 SUBMIT → APPROVE → DONE
        transition(member, teamId, itemId, "SUBMIT", itemVersion(member, teamId, itemId), null);
        MvcResult approve = transition(leader, teamId, itemId, "APPROVE", itemVersion(leader, teamId, itemId), null);
        assertThat(approve.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(approve.getResponse().getContentAsString(), "$.status")).isEqualTo("DONE");
    }

    // ---------- 8. 版本冲突 409：PATCH 旧版本 + 双人同版本 CLAIM ----------

    @Test
    void versionConflicts() throws Exception {
        MockHttpSession leader = register("leader4", "组长四", "passw0rd1!");
        MvcResult team = createTeam(leader, "冲突组", "CNF", false);
        String tb = team.getResponse().getContentAsString();
        long teamId = ((Number) JsonPath.read(tb, "$.id")).longValue();
        String invite = JsonPath.read(tb, "$.inviteCode");

        MockHttpSession m1 = register("member4a", "成员四甲", "passw0rd1!");
        MockHttpSession m2 = register("member4b", "成员四乙", "passw0rd1!");
        for (MockHttpSession s : new MockHttpSession[]{m1, m2}) {
            mvc.perform(post("/api/v1/teams/join").session(s).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"inviteCode\":\"" + invite + "\"}"))
                    .andExpect(status().isOk());
        }

        long itemId = createItem(leader, teamId, "{\"type\":\"ADHOC\",\"title\":\"冲突事项\",\"destination\":\"POOL\"}");
        int v0 = itemVersion(leader, teamId, itemId);
        // PATCH 成功一次
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId).session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v0 + ",\"title\":\"改名一次\"}"))
                .andExpect(status().isOk());
        // 再用旧 version PATCH → 409
        mvc.perform(patch("/api/v1/teams/" + teamId + "/items/" + itemId).session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + v0 + ",\"title\":\"改名两次\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 双人同 version CLAIM：第二人 409 且提示已被认领
        long item2 = createItem(leader, teamId, "{\"type\":\"ADHOC\",\"title\":\"认领竞争\",\"destination\":\"POOL\"}");
        int cv = itemVersion(leader, teamId, item2);
        MvcResult first = transition(m1, teamId, item2, "CLAIM", cv, null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        MvcResult second = transition(m2, teamId, item2, "CLAIM", cv, null);
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        String secondBody = second.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat((String) JsonPath.read(secondBody, "$.code")).isEqualTo("VERSION_CONFLICT");
        assertThat((String) JsonPath.read(secondBody, "$.message")).contains("已被他人认领");
    }

    // ---------- 9. M1 收尾：检查清单增删改查 + 真实持久化（事项详情返回清单） ----------

    @Test
    void checklistCrudAndPersistence() throws Exception {
        MockHttpSession leader = register("leader5", "组长五", "passw0rd1!");
        MvcResult team = createTeam(leader, "清单组", "CHK", false);
        String tb = team.getResponse().getContentAsString();
        long teamId = ((Number) JsonPath.read(tb, "$.id")).longValue();
        String invite = JsonPath.read(tb, "$.inviteCode");
        long itemId = createItem(leader, teamId, "{\"type\":\"ADHOC\",\"title\":\"清单事项\",\"destination\":\"POOL\"}");
        String base = "/api/v1/teams/" + teamId + "/items/" + itemId + "/checklist";

        // 新增两条（显式排序乱序提交，读取须按 sortOrder 升序）
        MvcResult e2 = mvc.perform(post(base).session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"第二步联调\",\"sortOrder\":2}"))
                .andExpect(status().isCreated()).andReturn();
        long entry2 = ((Number) JsonPath.read(e2.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(post(base).session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"第一步设计\",\"sortOrder\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.done").value(false));

        MvcResult list = mvc.perform(get(base).session(leader)).andExpect(status().isOk()).andReturn();
        String listBody = list.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(listBody, "$[0].text")).isEqualTo("第一步设计");
        assertThat((String) JsonPath.read(listBody, "$[1].text")).isEqualTo("第二步联调");
        long entry1 = ((Number) JsonPath.read(listBody, "$[0].id")).longValue();

        // 事项详情返回清单（持久化数据的同一来源）
        String detail = getItemJson(leader, teamId, itemId);
        assertThat(((Number) JsonPath.read(detail, "$.checklist.length()")).intValue()).isEqualTo(2);
        assertThat((String) JsonPath.read(detail, "$.checklist[0].text")).isEqualTo("第一步设计");

        // 改：文字 + 完成状态
        mvc.perform(patch(base + "/" + entry1).session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"第一步设计已确认\",\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("第一步设计已确认"))
                .andExpect(jsonPath("$.done").value(true));

        // 无编辑权限的普通成员新增 → 403（权限同编辑事项：创建人/负责人/PO/组长）
        MockHttpSession member = register("member5", "成员五", "passw0rd1!");
        mvc.perform(post("/api/v1/teams/join").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(base).session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"越权新增\"}"))
                .andExpect(status().isForbidden());
        // 空文字 → 400
        mvc.perform(post(base).session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // 删一条 → 204；再查只剩一条且详情同步（真实持久化，非本地态）
        mvc.perform(delete(base + "/" + entry2).session(leader).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(base).session(leader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].text").value("第一步设计已确认"));
        String detail2 = getItemJson(leader, teamId, itemId);
        assertThat(((Number) JsonPath.read(detail2, "$.checklist.length()")).intValue()).isEqualTo(1);
        // 删不存在的条目 → 404
        mvc.perform(delete(base + "/" + entry2).session(leader).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---------- 10. M1 收尾：小组代号全平台唯一（不区分大小写），冲突 409 ----------

    @Test
    void teamCodeDuplicateCaseInsensitiveConflict() throws Exception {
        MockHttpSession a = register("codeowner", "代号甲", "passw0rd1!");
        createTeam(a, "代号组", "DUP", false);

        MockHttpSession b = register("codeother", "代号乙", "passw0rd1!");
        // 完全相同代号
        mvc.perform(post("/api/v1/teams").session(b).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"撞号组\",\"code\":\"DUP\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_CODE_TAKEN"));
        // 大小写变体同样被拒
        mvc.perform(post("/api/v1/teams").session(b).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"撞号组二\",\"code\":\"dup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_CODE_TAKEN"));
        mvc.perform(post("/api/v1/teams").session(a).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"撞号组三\",\"code\":\"Dup\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_CODE_TAKEN"));
        // 不同代号正常创建
        createTeam(b, "正常组", "OKC", false);
    }

    // ---------- 11. M1 收尾：转交待认领事项 = 指派（设负责人后进入待开始） ----------

    @Test
    void transferUnclaimedActsAsAssign() throws Exception {
        MockHttpSession leader = register("leader6", "组长六", "passw0rd1!");
        MvcResult team = createTeam(leader, "转交组", "TRF", false);
        String tb = team.getResponse().getContentAsString();
        long teamId = ((Number) JsonPath.read(tb, "$.id")).longValue();
        String invite = JsonPath.read(tb, "$.inviteCode");
        long leaderId = myId(leader);

        MockHttpSession member = register("member6", "成员六", "passw0rd1!");
        long memberId = myId(member);
        mvc.perform(post("/api/v1/teams/join").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk());

        long itemId = createItem(leader, teamId, "{\"type\":\"ADHOC\",\"title\":\"待认领转交\",\"destination\":\"POOL\"}");
        String before = getItemJson(leader, teamId, itemId);
        assertThat((String) JsonPath.read(before, "$.status")).isEqualTo("UNCLAIMED");

        // 组长对待认领事项 TRANSFER → 视同指派：负责人=成员，状态 NOT_STARTED
        MvcResult tr = transition(leader, teamId, itemId, "TRANSFER",
                itemVersion(leader, teamId, itemId), "\"newAssigneeId\":" + memberId);
        assertThat(tr.getResponse().getStatus()).isEqualTo(200);
        String trBody = tr.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(trBody, "$.status")).isEqualTo("NOT_STARTED");
        assertThat(((Number) JsonPath.read(trBody, "$.ownerIds[0]")).longValue()).isEqualTo(memberId);

        // 已有负责人的事项再 TRANSFER → 状态不变（仍 NOT_STARTED），只换负责人
        MvcResult tr2 = transition(leader, teamId, itemId, "TRANSFER",
                itemVersion(leader, teamId, itemId), "\"newAssigneeId\":" + leaderId);
        assertThat(tr2.getResponse().getStatus()).isEqualTo(200);
        String tr2Body = tr2.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(tr2Body, "$.status")).isEqualTo("NOT_STARTED");
        assertThat(((Number) JsonPath.read(tr2Body, "$.ownerIds[0]")).longValue()).isEqualTo(leaderId);
    }
}
