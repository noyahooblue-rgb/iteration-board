package com.iterationboard;

import com.iterationboard.common.PlatformTime;
import com.iterationboard.common.Workdays;
import com.iterationboard.repo.NotificationRepo;
import com.iterationboard.repo.TeamRepo;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 1.1.0 回归测试（修订三 + 修订四后端）：P01 管理员引导、P05 离组交接、意见反馈、
 * P06/P07/P12/P13/P14/P15/P17/P18/P19/P20/P25/P04/P23 与系统信息/更新说明。
 * 全部测试数据为虚构账号与事项。MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V110IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepo notificationRepo;
    @Autowired TeamRepo teamRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.iterationboard.reminder.LeaveReminderService leaveReminderService;
    @Autowired com.iterationboard.reminder.ReminderService reminderService;
    @Autowired com.iterationboard.admin.AdminBootstrapTxService bootstrapTxService;
    @Autowired com.iterationboard.admin.ResetAdminOpsService resetAdminOpsService;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired PlatformTime platformTime;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

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
                                + "\",\"password\":\"passw0rd1!\",\"captchaId\":\"" + c[0]
                                + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        return session;
    }

    private MockHttpSession login(String loginName, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
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

    private void joinDirect(MockHttpSession session, String invite) throws Exception {
        mvc.perform(post("/api/v1/teams/join").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(true));
    }

    private void setRole(MockHttpSession leader, long teamId, long userId, String role) throws Exception {
        mvc.perform(patch("/api/v1/teams/" + teamId + "/members/" + userId + "/role").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
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

    private int itemVersion(MockHttpSession s, long teamId, long itemId) throws Exception {
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

    private String notificationsJson(MockHttpSession s, long teamId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/notifications").session(s).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    /** 在库中种入 admin 并完成首次改密，返回可用会话（rc2 起 HTTP 引导接口已删除，建号走应用内 Runner）。 */
    private MockHttpSession bootstrapAdmin() throws Exception {
        jdbc.update("insert into app_user (login_name, display_name, password_hash, avatar_color, platform_admin, status,"
                        + " must_change_password, failed_attempts, password_version, created_at, updated_at)"
                        + " values ('admin', '平台管理员', ?, 'indigo', true, 'ACTIVE', true, 0, 0, now(), now())",
                passwordEncoder.encode("InitPass1234"));
        MockHttpSession admin = login("admin", "InitPass1234");
        mvc.perform(post("/api/v1/auth/change-password").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"InitPass1234\",\"newPassword\":\"NewPass1234!\"}"))
                .andExpect(status().isNoContent());
        return admin;
    }

    // ---------- P01 ----------

    @Test
    void p01_bootstrapRunner_createsAdmin_forceChangePassword_oneShot() throws Exception {
        // rc2（hsa43amn）：建号走应用内 AdminBootstrapTxService（AdminBootstrapRunner 的事务部分），
        // HTTP 引导接口已删除（其不可达由 AdminEndpointSecurityTest 断言）。
        var result = bootstrapTxService.bootstrapAdmin();
        assertThat(result.outcome())
                .isEqualTo(com.iterationboard.admin.AdminBootstrapTxService.Outcome.CREATED);
        String initPw = result.generatedPassword();
        assertThat(initPw).isNotBlank();
        Long adminRow = jdbc.queryForObject(
                "select count(*) from app_user where login_name='admin' and platform_admin and must_change_password",
                Long.class);
        assertThat(adminRow).isEqualTo(1L);

        // 一次性：admin 已是平台管理员时再次初始化 → ALREADY_ADMIN，不改动账号
        var again = bootstrapTxService.bootstrapAdmin();
        assertThat(again.outcome())
                .isEqualTo(com.iterationboard.admin.AdminBootstrapTxService.Outcome.ALREADY_ADMIN);

        // 未改密前只能调改密类接口
        MockHttpSession admin = login("admin", initPw);
        mvc.perform(get("/api/v1/teams/mine").session(admin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MUST_CHANGE_PASSWORD"));

        mvc.perform(post("/api/v1/auth/change-password").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + initPw + "\",\"newPassword\":\"NewPass1234!\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/teams/mine").session(admin)).andExpect(status().isOk());
        Boolean flag = jdbc.queryForObject(
                "select must_change_password from app_user where login_name='admin'", Boolean.class);
        assertThat(flag).isFalse();
    }

    @Test
    void p01_registerNeverAutoAdmin_andResetAdmin() throws Exception {
        MockHttpSession first = register("v110first", "虚构首位注册者");
        MvcResult me = mvc.perform(get("/api/v1/auth/me").session(first)).andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(me.getResponse().getContentAsString(), "$.platformAdmin")).isFalse();

        // rc2：应急重置走运维命令的服务层 ResetAdminOpsService（--ib.ops=reset-admin），
        // 设为平台管理员 + 生成新密码 + 强制改密，并写审计 ADMIN_RESET_VIA_OPS
        String generated = resetAdminOpsService.resetAdmin("v110first");
        assertThat(generated).isNotBlank();
        Long auditCount = jdbc.queryForObject(
                "select count(*) from audit_log where action='ADMIN_RESET_VIA_OPS'", Long.class);
        assertThat(auditCount).isGreaterThanOrEqualTo(1L);
        // 旧密码失效
        mvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"v110first\",\"password\":\"passw0rd1!\"}"))
                .andExpect(status().isUnauthorized());
        // 新生成密码可登录，且为平台管理员 + 强制改密
        MockHttpSession s = login("v110first", generated);
        MvcResult me2 = mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(me2.getResponse().getContentAsString(), "$.platformAdmin")).isTrue();
        assertThat((Boolean) JsonPath.read(me2.getResponse().getContentAsString(), "$.mustChangePassword")).isTrue();
    }

    @Test
    void p01_grantRevoke_lastAdminProtected_adminNotDeletable() throws Exception {
        MockHttpSession admin = bootstrapAdmin();
        long adminId = myId(admin);
        MockHttpSession userB = register("v110userb", "虚构用户乙");
        long bId = myId(userB);

        // 授予 → 成功
        mvc.perform(patch("/api/v1/admin/users/" + bId + "/platform-admin").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platformAdmin\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.platformAdmin").value(true));
        // 撤销 B → 成功（admin 仍在）
        mvc.perform(patch("/api/v1/admin/users/" + bId + "/platform-admin").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platformAdmin\":false}"))
                .andExpect(status().isOk());
        // 撤销最后一名管理员（自己）→ 400
        mvc.perform(patch("/api/v1/admin/users/" + adminId + "/platform-admin").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platformAdmin\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_PLATFORM_ADMIN"));
        // 停用最后一名管理员 → 400
        mvc.perform(patch("/api/v1/admin/users/" + adminId + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LAST_PLATFORM_ADMIN"));
        // admin 不可删除
        mvc.perform(delete("/api/v1/admin/users/" + adminId).session(admin).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADMIN_NOT_DELETABLE"));
        // 普通用户无权授予
        mvc.perform(patch("/api/v1/admin/users/" + bId + "/platform-admin").session(userB).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"platformAdmin\":true}"))
                .andExpect(status().isForbidden());
    }

    // ---------- P05 ----------

    @Test
    void p05_handover_blocksLeave_returnAllClears() throws Exception {
        MockHttpSession leader = register("v110hlead", "虚构交接组长");
        TeamCtx team = createTeam(leader, "虚构交接组", "HDA");
        long leaderId = myId(leader);
        MockHttpSession member = register("v110hmem", "虚构交接成员");
        long memberId = myId(member);
        joinDirect(member, team.invite());

        long itemId = createItem(leader, team.teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构待交接事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + memberId + "]}");

        MvcResult pending = mvc.perform(get("/api/v1/teams/" + team.teamId() + "/members/" + memberId + "/handover-pending")
                        .session(leader))
                .andExpect(status().isOk()).andReturn();
        String pb = pending.getResponse().getContentAsString();
        assertThat(pb).contains("\"kind\":\"OWNED\"");
        assertThat(pb).contains("\"itemId\":" + itemId);

        // 未交接完禁止退出
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/leave").session(member).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HANDOVER_PENDING"));

        // 一键退回认领池：负责人清空、验收人改为组长
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/members/" + memberId + "/handover/return-all")
                        .session(leader).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPending").value(0));
        String itemJson = getItemJson(leader, team.teamId(), itemId);
        assertThat((String) JsonPath.read(itemJson, "$.status")).isEqualTo("UNCLAIMED");
        assertThat(((Number) JsonPath.read(itemJson, "$.acceptorId")).longValue()).isEqualTo(leaderId);

        // 交接完成后可退出
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/leave").session(member).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void p05_handover_transfer_andRemoveMemberBlocked() throws Exception {
        MockHttpSession leader = register("v110tlead", "虚构转交组长");
        TeamCtx team = createTeam(leader, "虚构转交组", "HDB");
        long leaderId = myId(leader);
        MockHttpSession member = register("v110tmem", "虚构转交成员");
        long memberId = myId(member);
        joinDirect(member, team.invite());
        long itemId = createItem(leader, team.teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构转交事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + memberId + "]}");

        // 组长移出被交接校验拦截
        mvc.perform(delete("/api/v1/teams/" + team.teamId() + "/members/" + memberId).session(leader).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HANDOVER_PENDING"));

        // 逐条转交给组长
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/members/" + memberId + "/handover")
                        .session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actions\":[{\"itemId\":" + itemId + ",\"action\":\"TRANSFER\",\"targetUserId\":" + leaderId + "}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingPending").value(0));
        String itemJson = getItemJson(leader, team.teamId(), itemId);
        assertThat(((Number) JsonPath.read(itemJson, "$.ownerIds[0]")).longValue()).isEqualTo(leaderId);

        // 现在可移出
        mvc.perform(delete("/api/v1/teams/" + team.teamId() + "/members/" + memberId).session(leader).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void p05_disableAccount_blockedByHandoverInAnyTeam() throws Exception {
        MockHttpSession admin = bootstrapAdmin();
        MockHttpSession leader = register("v110dlead", "虚构停用组长");
        TeamCtx team = createTeam(leader, "虚构停用组", "HDC");
        MockHttpSession member = register("v110dmem", "虚构停用成员");
        long memberId = myId(member);
        joinDirect(member, team.invite());
        createItem(leader, team.teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构停用阻塞事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + memberId + "]}");

        mvc.perform(patch("/api/v1/admin/users/" + memberId + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HANDOVER_PENDING"));

        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/members/" + memberId + "/handover/return-all")
                        .session(leader).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/users/" + memberId + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        // 停用后不能登录
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"v110dmem\",\"password\":\"passw0rd1!\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 意见反馈 ----------

    @Test
    void feedback_fullFlow() throws Exception {
        MockHttpSession admin = bootstrapAdmin();
        MockHttpSession author = register("v110fauthor", "虚构反馈人");
        MockHttpSession other = register("v110fother", "虚构围观人");

        // rc3（契约 3）：服务端按魔数判定图片格式，测试数据须带真实 PNG 文件头
        String png = Base64.getEncoder().encodeToString(new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4});
        MvcResult created = mvc.perform(post("/api/v1/feedback").session(author).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUGGESTION\",\"title\":\"虚构建议标题\",\"description\":\"虚构描述内容\","
                                + "\"pageUrl\":\"/teams\",\"browserInfo\":\"TestBrowser 1.0\","
                                + "\"images\":[{\"fileName\":\"shot.png\",\"contentType\":\"image/png\",\"contentBase64\":\"" + png + "\"}]}"))
                .andExpect(status().isCreated()).andReturn();
        String cb = created.getResponse().getContentAsString();
        long fid = ((Number) JsonPath.read(cb, "$.id")).longValue();
        assertThat((String) JsonPath.read(cb, "$.status")).isEqualTo("NEW");
        assertThat((String) JsonPath.read(cb, "$.appVersion")).isEqualTo("1.1.0");
        assertThat(((Number) JsonPath.read(cb, "$.images.length()")).intValue()).isEqualTo(1);

        // +1 防重复：点两次只计一次
        mvc.perform(post("/api/v1/feedback/" + fid + "/plus-one").session(other).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plusOneCount").value(1));
        mvc.perform(post("/api/v1/feedback/" + fid + "/plus-one").session(other).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.plusOneCount").value(1));

        // 评论
        mvc.perform(post("/api/v1/feedback/" + fid + "/comments").session(other).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"虚构评论\"}"))
                .andExpect(status().isCreated());

        // 提交人可改自己未处理的反馈；他人不可改
        mvc.perform(patch("/api/v1/feedback/" + fid).session(author).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"虚构建议标题改\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("虚构建议标题改"));
        mvc.perform(patch("/api/v1/feedback/" + fid).session(other).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"越权改\"}"))
                .andExpect(status().isForbidden());

        // 非管理员不能改状态
        mvc.perform(patch("/api/v1/feedback/" + fid + "/status").session(author).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ANALYZING\"}"))
                .andExpect(status().isForbidden());
        // 管理员：NEW 不能直跳 ACCEPTED
        mvc.perform(patch("/api/v1/feedback/" + fid + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACCEPTED\",\"plannedVersion\":\"1.2.0\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(patch("/api/v1/feedback/" + fid + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ANALYZING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANALYZING"));
        // 不采纳必填原因
        mvc.perform(patch("/api/v1/feedback/" + fid + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/feedback/" + fid + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\",\"rejectReason\":\"虚构不采纳原因\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // 状态变化通知提交人与 +1 的人
        String authorNotifs = mvc.perform(get("/api/v1/notifications").session(author))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(authorNotifs).contains("\"type\":\"FEEDBACK_STATUS\"");
        String otherNotifs = mvc.perform(get("/api/v1/notifications").session(other))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(otherNotifs).contains("\"type\":\"FEEDBACK_STATUS\"");

        // 已处理后提交人不能再改
        mvc.perform(patch("/api/v1/feedback/" + fid).session(author).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"再改\"}"))
                .andExpect(status().isConflict());

        // 列表筛选 + 导出 zip（仅管理员）
        mvc.perform(get("/api/v1/feedback").session(other).param("status", "REJECTED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value((int) fid));
        mvc.perform(post("/api/v1/feedback/export").session(other).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + fid + "]}"))
                .andExpect(status().isForbidden());
        MvcResult zip = mvc.perform(post("/api/v1/feedback/export").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[" + fid + "]}"))
                .andExpect(status().isOk()).andReturn();
        byte[] bytes = zip.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(100);
        assertThat(new String(bytes, 0, 2)).isEqualTo("PK");
    }

    @Test
    void feedback_dailyLimitAndValidation() throws Exception {
        MockHttpSession author = register("v110flimit", "虚构高产反馈人");
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/v1/feedback").session(author).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"OTHER\",\"title\":\"虚构反馈" + i + "\",\"description\":\"虚构描述\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/v1/feedback").session(author).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"OTHER\",\"title\":\"虚构第21条\",\"description\":\"虚构描述\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("FEEDBACK_DAILY_LIMIT"));
        // 描述必填
        MockHttpSession author2 = register("v110fvalid", "虚构校验人");
        mvc.perform(post("/api/v1/feedback").session(author2).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ISSUE\",\"title\":\"缺描述\"}"))
                .andExpect(status().isBadRequest());
        // 图片超 5 张
        String png = Base64.getEncoder().encodeToString(new byte[]{9});
        StringBuilder imgs = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) imgs.append(",");
            imgs.append("{\"fileName\":\"a.png\",\"contentType\":\"image/png\",\"contentBase64\":\"").append(png).append("\"}");
        }
        mvc.perform(post("/api/v1/feedback").session(author2).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ISSUE\",\"title\":\"图太多\",\"description\":\"虚构描述\",\"images\":[" + imgs + "]}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- P06 / P17 ----------

    private record Trio(MockHttpSession leader, long leaderId, MockHttpSession po, long poId,
                        MockHttpSession member, long memberId, TeamCtx team) {}

    private Trio trio(String prefix, String code) throws Exception {
        MockHttpSession leader = register(prefix + "lead", "虚构组长");
        TeamCtx team = createTeam(leader, "虚构组" + code, code);
        long leaderId = myId(leader);
        MockHttpSession po = register(prefix + "po", "虚构产品负责人");
        long poId = myId(po);
        joinDirect(po, team.invite());
        setRole(leader, team.teamId(), poId, "PO");
        MockHttpSession member = register(prefix + "mem", "虚构成员");
        long memberId = myId(member);
        joinDirect(member, team.invite());
        return new Trio(leader, leaderId, po, poId, member, memberId, team);
    }

    @Test
    void p06_leaderCanCreateTaskAndStory_memberCannot_aiSplitForLeader() throws Exception {
        Trio t = trio("v110s", "PSA");
        // 组长可创建任务（P06 放宽）
        long taskId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"TASK\",\"title\":\"虚构任务甲\"}");
        // 组长可创建故事
        long storyId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"STORY\",\"title\":\"虚构故事甲\",\"parentId\":" + taskId + ",\"storyCategory\":\"DEV\"}");
        assertThat(storyId).isPositive();
        // 普通成员仍不可创建任务
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/items").session(t.member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TASK\",\"title\":\"成员越权任务\"}"))
                .andExpect(status().isForbidden());
        // 组长可用 AI 拆故事（mock 模式）；成员不可
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/ai/split-stories").session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + taskId + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/ai/split-stories").session(t.member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":" + taskId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void p17_defaultAcceptorByRole_selfAcceptAutoTransfer_taskApprovedByLeader() throws Exception {
        Trio t = trio("v110a", "PAB");
        long taskId = createItem(t.po(), t.team().teamId(), "{\"type\":\"TASK\",\"title\":\"虚构任务乙\"}");
        // 故事默认验收人 = 故事默认验收角色（PO）的成员，而非创建人规则之外的人
        long storyId = createItem(t.po(), t.team().teamId(),
                "{\"type\":\"STORY\",\"title\":\"虚构故事乙\",\"parentId\":" + taskId + ",\"storyCategory\":\"DEV\","
                        + "\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.poId() + "]}");
        String storyJson = getItemJson(t.po(), t.team().teamId(), storyId);
        assertThat(((Number) JsonPath.read(storyJson, "$.acceptorId")).longValue()).isEqualTo(t.poId());

        // PO 自己负责又任验收人：提交时自动转交组长验收
        progress(t.po(), t.team().teamId(), storyId, "虚构进展");
        MvcResult sub = transition(t.po(), t.team().teamId(), storyId, "SUBMIT",
                itemVersion(t.po(), t.team().teamId(), storyId), null);
        assertThat(sub.getResponse().getStatus()).isEqualTo(200);
        String subBody = sub.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(subBody, "$.status")).isEqualTo("IN_REVIEW");
        assertThat(((Number) JsonPath.read(subBody, "$.acceptorId")).longValue()).isEqualTo(t.leaderId());
        assertThat((String) JsonPath.read(subBody, "$.acceptorNote")).contains("自动转交");

        // 负责人本人不能验收自己的事项
        MvcResult selfApprove = transition(t.po(), t.team().teamId(), storyId, "APPROVE",
                itemVersion(t.po(), t.team().teamId(), storyId), null);
        assertThat(selfApprove.getResponse().getStatus()).isEqualTo(403);

        // 组长验收通过 → 故事完成 → 任务进入待验收并通知任务验收人（默认组长）
        MvcResult ap = transition(t.leader(), t.team().teamId(), storyId, "APPROVE",
                itemVersion(t.leader(), t.team().teamId(), storyId), null);
        assertThat(ap.getResponse().getStatus()).isEqualTo(200);
        String taskJson = getItemJson(t.leader(), t.team().teamId(), taskId);
        assertThat((String) JsonPath.read(taskJson, "$.taskStatus")).isEqualTo("PENDING_ACCEPTANCE");
        assertThat(notificationRepo.countByRecipientIdAndTypeAndItemId(t.leaderId(), "TASK_PENDING_ACCEPTANCE", taskId))
                .isGreaterThanOrEqualTo(1L);

        // 组长可验收任务（P17，不再仅 PO）
        MvcResult taskAp = transition(t.leader(), t.team().teamId(), taskId, "APPROVE",
                itemVersion(t.leader(), t.team().teamId(), taskId), null);
        assertThat(taskAp.getResponse().getStatus()).isEqualTo(200);
        assertThat((String) JsonPath.read(taskAp.getResponse().getContentAsString(), "$.status")).isEqualTo("DONE");
    }

    @Test
    void p17_teamSettingsAcceptorRoles() throws Exception {
        Trio t = trio("v110r", "PAC");
        // 小组设置：故事默认验收角色改为组长
        mvc.perform(patch("/api/v1/teams/" + t.team().teamId()).session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"storyAcceptorRole\":\"LEADER\",\"taskAcceptorRole\":\"PO\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.storyAcceptorRole").value("LEADER"))
                .andExpect(jsonPath("$.settings.taskAcceptorRole").value("PO"));
        long taskId = createItem(t.po(), t.team().teamId(), "{\"type\":\"TASK\",\"title\":\"虚构任务丙\"}");
        long storyId = createItem(t.po(), t.team().teamId(),
                "{\"type\":\"STORY\",\"title\":\"虚构故事丙\",\"parentId\":" + taskId + ",\"storyCategory\":\"DEV\"}");
        String storyJson = getItemJson(t.po(), t.team().teamId(), storyId);
        assertThat(((Number) JsonPath.read(storyJson, "$.acceptorId")).longValue()).isEqualTo(t.leaderId());
    }

    // ---------- P07 / P12 / P13 ----------

    @Test
    void p07_planningIterationItems_noRemindersOrRiskSignals() throws Exception {
        Trio t = trio("v110p", "PAD");
        long iterationId = createIteration(t.leader(), t.team().teamId(), "虚构规划迭代");
        // 规划中迭代内的 P0 待认领事项 + 对照：无迭代的 P0 待认领事项
        long planningItem = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构规划期内事项\",\"priority\":\"P0\",\"iterationId\":" + iterationId + "}");
        long controlItem = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构对照事项\",\"priority\":\"P0\"}");
        // 把两条事项的创建时间拨回 10 天前，满足未认领阈值
        jdbc.update("update item set created_at = now() - interval '10 days' where id in (?, ?)", planningItem, controlItem);
        entityManager.flush();
        entityManager.clear();

        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/reminders/scan").session(t.leader()).with(csrf()))
                .andExpect(status().isOk());
        String notifs = notificationsJson(t.leader(), t.team().teamId());
        assertThat(notifs).contains("\"itemId\":" + controlItem);
        assertThat(notifs).doesNotContain("\"itemId\":" + planningItem);

        MvcResult ov = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/overview").session(t.leader()))
                .andExpect(status().isOk()).andReturn();
        String risks = ov.getResponse().getContentAsString();
        assertThat(risks).contains("UNCLAIMED_PRIORITY");
        // 风险信号中规划迭代事项不出现：以 itemId 判定（总览成员卡片含事项 DTO，故只查 risks 片段）
        String risksPart = risks.substring(risks.indexOf("\"risks\""));
        assertThat(risksPart).doesNotContain("\"itemId\":" + planningItem);
    }

    private long createIteration(MockHttpSession leader, long teamId, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams/" + teamId + "/iterations").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"startDate\":\"2026-11-23\",\"endDate\":\"2026-12-04\"}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    @Test
    void p12_standupDataAsOf_andRefreshThrottle() throws Exception {
        Trio t = trio("v110u", "PAE");
        MvcResult su = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/standup").session(t.member()))
                .andExpect(status().isOk()).andReturn();
        assertThat(su.getResponse().getContentAsString()).contains("\"dataAsOf\"");
        // 组长刷新成功；1 分钟内再次刷新被节流
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/standup/refresh").session(t.leader()).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/standup/refresh").session(t.leader()).with(csrf()))
                .andExpect(status().isTooManyRequests());
        // 成员无权刷新
        Trio t2 = trio("v110u2", "PAF");
        mvc.perform(post("/api/v1/teams/" + t2.team().teamId() + "/standup/refresh").session(t2.member()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void p13_iterationSummary_hasOutsideWorkSection() throws Exception {
        Trio t = trio("v110o", "PAG");
        // 独立事项（不入迭代）并完成
        long adhoc = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构迭代外事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.memberId() + "]}");
        progress(t.member(), t.team().teamId(), adhoc, "虚构进展");
        transition(t.member(), t.team().teamId(), adhoc, "SUBMIT", itemVersion(t.member(), t.team().teamId(), adhoc), null);
        transition(t.leader(), t.team().teamId(), adhoc, "APPROVE", itemVersion(t.leader(), t.team().teamId(), adhoc), null);

        long iterationId = createIteration(t.leader(), t.team().teamId(), "虚构总结迭代");
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/start")
                        .session(t.leader()).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/close")
                        .session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        MvcResult reports = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/reports").session(t.leader()))
                .andExpect(status().isOk()).andReturn();
        String rb = reports.getResponse().getContentAsString();
        assertThat(rb).contains("迭代外工作");
        assertThat(rb).contains("虚构迭代外事项");
    }

    // ---------- P14 / P15 ----------

    @Test
    void p14_batch_defaultExcludesCreator_includeCreatorOptIn() throws Exception {
        Trio t = trio("v110b", "PAH");
        MvcResult b1 = mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/dispatch-batches").session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ADHOC\",\"title\":\"虚构批次甲\",\"memberIds\":[" + t.memberId() + "," + t.leaderId() + "]}"))
                .andExpect(status().isCreated()).andReturn();
        // 名单含发布人但默认排除 → 只生成 1 份
        assertThat(((Number) JsonPath.read(b1.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(1);

        MvcResult b2 = mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/dispatch-batches").session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ADHOC\",\"title\":\"虚构批次乙\",\"memberIds\":[" + t.memberId() + "],\"includeCreator\":true}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(((Number) JsonPath.read(b2.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(2);
    }

    @Test
    void p15_nudgeBlocked_requiresReason_writtenToItem() throws Exception {
        Trio t = trio("v110n", "PAI");
        long itemId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构被催事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.memberId() + "]}");
        progress(t.member(), t.team().teamId(), itemId, "虚构进展");
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/items/" + itemId + "/nudge").session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        // 无原因点「卡住了」→ 400
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/items/" + itemId + "/nudge/respond").session(t.member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"BLOCKED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        // 带原因 → 写入阻塞原因
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/items/" + itemId + "/nudge/respond").session(t.member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"response\":\"BLOCKED\",\"reason\":\"虚构阻塞原因一句话\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockReason").value("虚构阻塞原因一句话"));
        String itemJson = getItemJson(t.member(), t.team().teamId(), itemId);
        assertThat((Boolean) JsonPath.read(itemJson, "$.blocked")).isTrue();
        assertThat((String) JsonPath.read(itemJson, "$.blockReason")).isEqualTo("虚构阻塞原因一句话");
    }

    // ---------- P18 ----------

    @Test
    void p18_closePreview_close_undoClose_restores() throws Exception {
        Trio t = trio("v110c", "PAJ");
        long iterationId = createIteration(t.leader(), t.team().teamId(), "虚构可撤销迭代");
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/start")
                        .session(t.leader()).with(csrf()))
                .andExpect(status().isOk());
        long story = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构结转事项\",\"iterationId\":" + iterationId + "}");

        MvcResult preview = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/close-preview")
                        .session(t.leader()))
                .andExpect(status().isOk()).andReturn();
        String pv = preview.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(pv, "$.unfinishedCount")).intValue()).isEqualTo(1);
        assertThat(pv).contains("迭代总结报告");

        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/close")
                        .session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decisions\":[{\"itemId\":" + story + ",\"action\":\"BACKLOG\"}],\"improvements\":[\"虚构改进项甲\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.improvementCreated").value(1))
                .andExpect(jsonPath("$.movedToBacklog").value(1));

        MvcResult undo = mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/undo-close")
                        .session(t.leader()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String ub = undo.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(ub, "$.restoredItems")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(ub, "$.deletedImprovementIds.length()")).intValue()).isEqualTo(1);
        assertThat((Boolean) JsonPath.read(ub, "$.reportDeleted")).isTrue();
        assertThat((String) JsonPath.read(ub, "$.iteration.status")).isEqualTo("ACTIVE");
        String itemJson = getItemJson(t.leader(), t.team().teamId(), story);
        assertThat(((Number) JsonPath.read(itemJson, "$.iterationId")).longValue()).isEqualTo(iterationId);

        // 已恢复为 ACTIVE，再撤销 → 422
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/undo-close")
                        .session(t.leader()).with(csrf()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void p18_undoClose_keepsOperatedImprovement() throws Exception {
        Trio t = trio("v110k", "PAK");
        long iterationId = createIteration(t.leader(), t.team().teamId(), "虚构改进项迭代");
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/start")
                        .session(t.leader()).with(csrf()))
                .andExpect(status().isOk());
        MvcResult close = mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/close")
                        .session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"improvements\":[\"虚构改进项乙\"]}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(close.getResponse().getContentAsString()).contains("\"improvementCreated\":1");
        // 找到改进项并被人认领（=已被操作）
        MvcResult items = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/items").session(t.leader()))
                .andExpect(status().isOk()).andReturn();
        String ib = items.getResponse().getContentAsString();
        java.util.List<Number> improvementIds = JsonPath.read(ib, "$[?(@.title=='虚构改进项乙')].id");
        long improvementId = improvementIds.get(0).longValue();
        transition(t.member(), t.team().teamId(), improvementId, "CLAIM",
                itemVersion(t.member(), t.team().teamId(), improvementId), null);

        MvcResult undo = mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/iterations/" + iterationId + "/undo-close")
                        .session(t.leader()).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String ub = undo.getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(ub, "$.keptImprovementIds.length()")).intValue()).isEqualTo(1);
        assertThat(((Number) JsonPath.read(ub, "$.deletedImprovementIds.length()")).intValue()).isEqualTo(0);
    }

    // ---------- P19 ----------

    @Test
    void p19_leaveDueItems_andPreLeaveReminder() throws Exception {
        Trio t = trio("v110l", "PAL");
        LocalDate today = platformTime.today();
        LocalDate due = today.plusDays(3);
        long itemId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构请假期间到期事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.memberId()
                        + "],\"dueDate\":\"" + due + "\"}");
        // 请假：开始日 = 今天之后第 2 个工作日，结束 +5 天
        LocalDate leaveStart = Workdays.plusWorkdays(today, 2, null);
        LocalDate leaveEnd = leaveStart.plusDays(5);
        mvc.perform(patch("/api/v1/teams/" + t.team().teamId() + "/members/" + t.memberId() + "/status")
                        .session(t.member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberStatus\":\"LEAVE\",\"memberStatusStart\":\"" + leaveStart
                                + "\",\"memberStatusEnd\":\"" + leaveEnd + "\"}"))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/members/" + t.memberId() + "/leave-due-items")
                        .session(t.member())
                        .param("start", leaveStart.toString()).param("end", leaveEnd.toString()))
                .andExpect(status().isOk()).andReturn();
        // 到期日在请假区间外时清单为空；在区间内才出现——本事项 due=today+3，可能落在区间内或外，按接口口径校验一致性
        boolean inRange = !due.isBefore(leaveStart) && !due.isAfter(leaveEnd);
        String rb = r.getResponse().getContentAsString();
        assertThat(rb.contains("\"itemId\":" + itemId)).isEqualTo(inRange);

        // 请假前一个工作日提醒：在 leaveStart 的前一个工作日触发服务
        LocalDate dayBefore = Workdays.plusWorkdays(today, 1, null);
        var team = teamRepo.findById(t.team().teamId()).orElseThrow();
        int sent = leaveReminderService.remindTeam(team, dayBefore);
        assertThat(sent).isGreaterThanOrEqualTo(1);
        String notifs = notificationsJson(t.member(), t.team().teamId());
        assertThat(notifs).contains("\"type\":\"REMINDER_LEAVE\"");
        String leaderNotifs = notificationsJson(t.leader(), t.team().teamId());
        assertThat(leaderNotifs).contains("\"type\":\"REMINDER_LEAVE\"");
    }

    // ---------- P20 ----------

    @Test
    void p20_reportDocxAndHtmlExport() throws Exception {
        Trio t = trio("v110w", "PAM");
        MvcResult gen = mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/reports/generate").session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"WEEKLY\",\"periodStart\":\"2026-11-16\",\"periodEnd\":\"2026-11-22\"}"))
                .andExpect(status().isCreated()).andReturn();
        long reportId = ((Number) JsonPath.read(gen.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult docx = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/reports/" + reportId + "/export-docx")
                        .session(t.leader()))
                .andExpect(status().isOk()).andReturn();
        byte[] bytes = docx.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(new String(bytes, 0, 2)).isEqualTo("PK");

        MvcResult html = mvc.perform(get("/api/v1/teams/" + t.team().teamId() + "/reports/" + reportId + "/export-html")
                        .session(t.leader()))
                .andExpect(status().isOk()).andReturn();
        String hb = html.getResponse().getContentAsString();
        assertThat(hb).contains("<h1>");
        assertThat(hb).contains("周报");
    }

    // ---------- P25 / P04 ----------

    @Test
    void p25_compactNotifications_andFiveMinuteMerge() throws Exception {
        Trio t = trio("v110q", "PAN");
        // 成员开启精简通知
        mvc.perform(patch("/api/v1/auth/notification-settings").session(t.member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"compactNotification\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compactNotification").value(true));
        long itemId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构精简通知事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.memberId() + "]}");
        // 指派类仍收
        assertThat(notificationRepo.countByRecipientIdAndTypeAndItemId(t.memberId(), "ASSIGN", itemId)).isEqualTo(1L);
        // 评论类（ITEM_UPDATE）在精简模式下不收
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/items/" + itemId + "/comments").session(t.leader()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"虚构评论一\"}"))
                .andExpect(status().isCreated());
        assertThat(notificationRepo.countByRecipientIdAndTypeAndItemId(t.memberId(), "COMMENT", itemId)).isEqualTo(0L);

        // 5 分钟合并：另一负责人收到同一事项两条评论通知应合并为一条
        Trio t2 = trio("v110q2", "PAO");
        long item2 = createItem(t2.leader(), t2.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构合并通知事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t2.memberId() + "]}");
        for (String c : new String[]{"虚构评论甲", "虚构评论乙"}) {
            mvc.perform(post("/api/v1/teams/" + t2.team().teamId() + "/items/" + item2 + "/comments").session(t2.leader()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + c + "\"}"))
                    .andExpect(status().isCreated());
        }
        assertThat(notificationRepo.countByRecipientIdAndTypeAndItemId(t2.memberId(), "COMMENT", item2)).isEqualTo(1L);
    }

    @Test
    void p04_duplicateNames_flaggedWithLoginName() throws Exception {
        MockHttpSession leader = register("v110glead", "虚构组长");
        TeamCtx team = createTeam(leader, "虚构重名组", "PAP");
        MockHttpSession a = register("v110ga", "虚构重名");
        MockHttpSession b = register("v110gb", "虚构重名");
        joinDirect(a, team.invite());
        joinDirect(b, team.invite());
        MvcResult members = mvc.perform(get("/api/v1/teams/" + team.teamId() + "/members").session(leader))
                .andExpect(status().isOk()).andReturn();
        String mb = members.getResponse().getContentAsString();
        assertThat(mb).contains("\"loginName\":\"v110ga\"");
        assertThat(mb).contains("\"loginName\":\"v110gb\"");
        // 两名重名成员 duplicateName=true，组长为 false
        java.util.List<Boolean> flagsA = JsonPath.read(mb, "$[?(@.loginName=='v110ga')].duplicateName");
        java.util.List<Boolean> flagsB = JsonPath.read(mb, "$[?(@.loginName=='v110gb')].duplicateName");
        java.util.List<Boolean> flagsL = JsonPath.read(mb, "$[?(@.loginName=='v110glead')].duplicateName");
        assertThat(flagsA).containsExactly(true);
        assertThat(flagsB).containsExactly(true);
        assertThat(flagsL).containsExactly(false);
    }

    // ---------- 系统信息 / 更新说明 / 版本 ----------

    @Test
    void systemInfo_releaseNotes_backupStatus_metaVersion() throws Exception {
        MockHttpSession admin = bootstrapAdmin();
        MockHttpSession plain = register("v110plain", "虚构普通用户");

        mvc.perform(get("/api/v1/admin/system-info").session(plain)).andExpect(status().isForbidden());
        MvcResult info = mvc.perform(get("/api/v1/admin/system-info").session(admin))
                .andExpect(status().isOk()).andReturn();
        String ib = info.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(ib, "$.appVersion")).isEqualTo("1.1.0");
        assertThat((String) JsonPath.read(ib, "$.databaseVersion")).contains("PostgreSQL");
        // rc3：新增 V11 迁移后 flyway 版本为 11
        assertThat((String) JsonPath.read(ib, "$.flywayVersion")).isEqualTo("11");
        assertThat((String) JsonPath.read(ib, "$.serviceStartType")).isEqualTo("未知");
        assertThat(ib).contains("\"lanAddresses\"");
        assertThat(ib).contains("\"installDir\"");

        // P23：无备份记录文件时如实返回 stale=true
        mvc.perform(get("/api/v1/admin/backup-status").session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backupStale").value(true))
                .andExpect(jsonPath("$.statusFilePresent").value(false));

        // 更新说明：未读 → 标记已读 → 已读
        mvc.perform(get("/api/v1/release-notes").session(plain))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.1.0"))
                .andExpect(jsonPath("$.read").value(false))
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("意见反馈")));
        mvc.perform(post("/api/v1/release-notes/read").session(plain).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
        mvc.perform(get("/api/v1/release-notes").session(plain))
                .andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));

        // /meta 版本
        mvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.1.0"));
    }

    // ---------- rc2 裁定（hsa43amn 二.1 / 二.2） ----------

    @Test
    void rc2_unstart_restoresProgressBeforeStart_notZero() throws Exception {
        Trio t = trio("rc2u", "RCU");
        long itemId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构撤销开始事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.memberId() + "]}");
        // 模拟开始前已有进度值（如历史/导入数据）：直接置 30
        com.iterationboard.domain.Item it = entityManager.find(com.iterationboard.domain.Item.class, itemId);
        it.setProgressPercent(30);
        entityManager.flush();

        // 登记进展到 65 → 自动开始（快照开始前进度 30），状态进行中、进度 65
        mvc.perform(post("/api/v1/teams/" + t.team().teamId() + "/items/" + itemId + "/progress")
                        .session(t.member()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"虚构进展\",\"percent\":65}"))
                .andExpect(status().isCreated());
        String started = getItemJson(t.member(), t.team().teamId(), itemId);
        assertThat((String) JsonPath.read(started, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) JsonPath.read(started, "$.progressPercent")).intValue()).isEqualTo(65);

        // 撤销开始 → 回到待开始，进度恢复为开始前的 30（不是清零），流转历史保留
        MvcResult un = transition(t.member(), t.team().teamId(), itemId, "UNSTART",
                itemVersion(t.member(), t.team().teamId(), itemId), null);
        assertThat(un.getResponse().getStatus()).isEqualTo(200);
        String unstarted = getItemJson(t.member(), t.team().teamId(), itemId);
        assertThat((String) JsonPath.read(unstarted, "$.status")).isEqualTo("NOT_STARTED");
        assertThat(((Number) JsonPath.read(unstarted, "$.progressPercent")).intValue()).isEqualTo(30);
        Long hist = jdbc.queryForObject(
                "select count(*) from activity where item_id = ? and action = 'UNSTART'", Long.class, itemId);
        assertThat(hist).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void rc2_overdueReminders_twoStageOnceOnly() throws Exception {
        Trio t = trio("rc2o", "RCO");
        com.iterationboard.domain.Team team = teamRepo.findById(t.team().teamId()).orElseThrow();
        LocalDate today = platformTime.today();
        // 事项 A：昨天到期（逾期初期）→ 只发第一段（负责人一次），不抄送组长
        long aId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构刚逾期事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.memberId()
                        + "],\"dueDate\":\"" + today.minusDays(1) + "\"}");
        // 事项 B：10 天前到期（已过第 3 个工作日）→ 第一段 + 第二段（负责人 + 抄送组长）
        long bId = createItem(t.leader(), t.team().teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构久逾期事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + t.memberId()
                        + "],\"dueDate\":\"" + today.minusDays(10) + "\"}");

        reminderService.scanTeam(team, today);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE", aId)).isEqualTo(1);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE_ESCALATED", aId)).isZero();
        assertThat(overdueCount(t.leaderId(), "REMINDER_OVERDUE_ESCALATED", aId)).isZero();
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE", bId)).isEqualTo(1);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE_ESCALATED", bId)).isEqualTo(1);
        assertThat(overdueCount(t.leaderId(), "REMINDER_OVERDUE_ESCALATED", bId)).isEqualTo(1);

        // 同日重复扫描：两段都不得重复推送
        reminderService.scanTeam(team, today);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE", aId)).isEqualTo(1);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE", bId)).isEqualTo(1);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE_ESCALATED", bId)).isEqualTo(1);
        assertThat(overdueCount(t.leaderId(), "REMINDER_OVERDUE_ESCALATED", bId)).isEqualTo(1);

        // 一周后再扫：A 到达第 3 个工作日 → 补发第二段（负责人 + 组长各一次）；B 不再新增
        reminderService.scanTeam(team, today.plusDays(7));
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE", aId)).isEqualTo(1);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE_ESCALATED", aId)).isEqualTo(1);
        assertThat(overdueCount(t.leaderId(), "REMINDER_OVERDUE_ESCALATED", aId)).isEqualTo(1);
        assertThat(overdueCount(t.memberId(), "REMINDER_OVERDUE_ESCALATED", bId)).isEqualTo(1);
        assertThat(overdueCount(t.leaderId(), "REMINDER_OVERDUE_ESCALATED", bId)).isEqualTo(1);
    }

    private int overdueCount(long recipientId, String type, long itemId) {
        return notificationRepo.findRecent(recipientId, type, itemId, java.time.Instant.EPOCH).size();
    }
}
