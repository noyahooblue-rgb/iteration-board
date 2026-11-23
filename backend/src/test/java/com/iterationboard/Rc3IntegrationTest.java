package com.iterationboard;

import com.iterationboard.domain.Notification;
import com.iterationboard.repo.ItemRepo;
import com.iterationboard.repo.NotificationRepo;
import com.iterationboard.repo.TeamRepo;
import com.iterationboard.repo.UserRepo;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 修订六 rc3 后端回归（契约 1 登录过渡 / 契约 2 管理员账号管理 / 契约 3 反馈图片 / 契约 4 直达解析）。
 * MockMvc + 真实 PostgreSQL，事务回滚隔离；全部测试数据为虚构账号与事项。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Rc3IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepo userRepo;
    @Autowired ItemRepo itemRepo;
    @Autowired TeamRepo teamRepo;
    @Autowired NotificationRepo notificationRepo;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

    private static final String GOOD_PW = "passw0rd1!";
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

    // ---------- helpers（与 V110IntegrationTest 同口径） ----------

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
                                + "\",\"password\":\"" + GOOD_PW + "\",\"captchaId\":\"" + c[0]
                                + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        return session;
    }

    private MvcResult loginRaw(MockHttpSession session, String loginName, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private MvcResult loginRaw(String loginName, String password) throws Exception {
        return loginRaw(new MockHttpSession(), loginName, password);
    }

    private MockHttpSession loginOk(String loginName, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult r = loginRaw(session, loginName, password);
        assertThat(r.getResponse().getStatus()).as("登录应成功: %s", loginName).isEqualTo(200);
        return session;
    }

    private long myId(MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** 直接造数（绕过注册校验）：老账号/管理员场景用。 */
    private long insertUser(String loginName, String rawPassword, boolean platformAdmin, boolean mustChange) {
        jdbc.update("insert into app_user (login_name, display_name, password_hash, avatar_color, platform_admin, status,"
                        + " must_change_password, failed_attempts, password_version, created_at, updated_at)"
                        + " values (?, ?, ?, 'indigo', ?, 'ACTIVE', ?, 0, 0, now(), now())",
                loginName, "虚构-" + loginName, passwordEncoder.encode(rawPassword), platformAdmin, mustChange);
        return userRepo.findByLoginName(loginName).orElseThrow().getId();
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

    private long createItem(MockHttpSession s, long teamId, String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long auditCount(String action, long targetId) {
        Long n = jdbc.queryForObject("select count(*) from audit_log where action = ? and target_id = ?",
                Long.class, action, targetId);
        return n == null ? 0 : n;
    }

    // ---------- 契约 1：登录过渡 ----------

    @Test
    void login_weakLegacyPassword_succeedsButForcesChange_compliantDoesNot() throws Exception {
        insertUser("rc3weak", "weakpass1", false, false);       // 旧规则弱密码：字母+数字、无特殊字符
        insertUser("rc3strong", "Strong#123", false, false);    // 新规则合规

        loginOk("rc3weak", "weakpass1");
        assertThat(jdbc.queryForObject("select must_change_password from app_user where login_name='rc3weak'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("select last_login_at from app_user where login_name='rc3weak'",
                java.sql.Timestamp.class)).isNotNull();

        loginOk("rc3strong", "Strong#123");
        assertThat(jdbc.queryForObject("select must_change_password from app_user where login_name='rc3strong'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("select last_login_at from app_user where login_name='rc3strong'",
                java.sql.Timestamp.class)).isNotNull();
    }

    // ---------- 契约 2：管理员账号管理 ----------

    @Test
    void adminResetPassword_fullFlow() throws Exception {
        insertUser("rc3admin", "Admin#1234", true, false);
        MockHttpSession admin = loginOk("rc3admin", "Admin#1234");
        long adminId = myId(admin);

        MockHttpSession targetSession = register("rc3target", "虚构被重置者");
        long targetId = myId(targetSession);
        int versionBefore = userRepo.findByLoginName("rc3target").orElseThrow().getPasswordVersion();

        // 普通成员 403
        MockHttpSession member = register("rc3member", "虚构普通成员");
        mvc.perform(post("/api/v1/admin/users/" + targetId + "/reset-password").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminPassword\":\"" + GOOD_PW + "\"}"))
                .andExpect(status().isForbidden());

        // 组长（非平台管理员）403：建组者是组长
        MockHttpSession leader = register("rc3leader", "虚构组长");
        createTeam(leader, "虚构重置组", "RCRL").teamId();
        mvc.perform(post("/api/v1/admin/users/" + targetId + "/reset-password").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminPassword\":\"" + GOOD_PW + "\"}"))
                .andExpect(status().isForbidden());

        // 二次确认密码错误 → 400 且不执行（旧密码仍可登录）
        mvc.perform(post("/api/v1/admin/users/" + targetId + "/reset-password").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminPassword\":\"Wrong#999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ADMIN_PASSWORD"));
        loginOk("rc3target", GOOD_PW);

        // 重置自己被拒
        mvc.perform(post("/api/v1/admin/users/" + adminId + "/reset-password").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminPassword\":\"Admin#1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_RESET_SELF"));

        // 正常重置
        MvcResult reset = mvc.perform(post("/api/v1/admin/users/" + targetId + "/reset-password").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminPassword\":\"Admin#1234\"}"))
                .andExpect(status().isOk()).andReturn();
        String temp = JsonPath.read(reset.getResponse().getContentAsString(), "$.temporaryPassword");
        assertThat(temp).hasSize(16);

        var after = userRepo.findByLoginName("rc3target").orElseThrow();
        assertThat(after.isMustChangePassword()).isTrue();
        assertThat(after.getPasswordVersion()).isEqualTo(versionBefore + 1);
        assertThat(after.getLockedUntil()).isNull();
        assertThat(after.getFailedAttempts()).isZero();
        assertThat(auditCount("RESET_USER_PASSWORD", targetId)).isEqualTo(1);
        assertThat(notificationRepo.findByRecipientId(targetId).stream()
                .anyMatch(n -> "PASSWORD_RESET".equals(n.getType()))).isTrue();

        // 旧会话失效（passwordVersion 变化被拦截器踢下线）
        mvc.perform(get("/api/v1/teams/mine").session(targetSession)).andExpect(status().isUnauthorized());
        // 旧密码失效
        assertThat(loginRaw("rc3target", GOOD_PW).getResponse().getStatus()).isEqualTo(401);
        // 临时密码可登录且被强制改密
        MockHttpSession tempSession = loginOk("rc3target", temp);
        MvcResult me = mvc.perform(get("/api/v1/auth/me").session(tempSession)).andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(me.getResponse().getContentAsString(), "$.mustChangePassword")).isTrue();
    }

    @Test
    void adminUnlock_andForceLogout_andUserListFields() throws Exception {
        insertUser("rc3admin2", "Admin#1234", true, false);
        MockHttpSession admin = loginOk("rc3admin2", "Admin#1234");
        MockHttpSession target = register("rc3lockme", "虚构被锁者");
        long targetId = myId(target);
        loginOk("rc3lockme", GOOD_PW); // 产生 lastLoginAt

        jdbc.update("update app_user set locked_until = now() + interval '15 minutes', failed_attempts = 5 where id = ?", targetId);
        entityManager.clear(); // jdbc 直改绕过 JPA 缓存，清后控制器读到最新行
        int versionBefore = userRepo.findByLoginName("rc3lockme").orElseThrow().getPasswordVersion();

        // 用户列表新字段
        MvcResult users = mvc.perform(get("/api/v1/admin/users").session(admin)).andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> rows = JsonPath.read(users.getResponse().getContentAsString(),
                "$[?(@.loginName=='rc3lockme')]");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("locked")).isEqualTo(true);
        assertThat(rows.get(0).get("mustChangePassword")).isEqualTo(false);
        assertThat(rows.get(0).get("lastLoginAt")).isNotNull();

        // 解除锁定
        mvc.perform(post("/api/v1/admin/users/" + targetId + "/unlock").session(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
        var unlocked = userRepo.findByLoginName("rc3lockme").orElseThrow();
        assertThat(unlocked.getLockedUntil()).isNull();
        assertThat(unlocked.getFailedAttempts()).isZero();
        assertThat(auditCount("UNLOCK_USER", targetId)).isEqualTo(1);

        // 非管理员调 unlock/force-logout → 403（须在强制下线前测，否则目标会话已失效只会 401）
        mvc.perform(post("/api/v1/admin/users/" + targetId + "/unlock").session(target).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/users/" + targetId + "/force-logout").session(target).with(csrf()))
                .andExpect(status().isForbidden());

        // 强制下线：passwordVersion+1
        mvc.perform(post("/api/v1/admin/users/" + targetId + "/force-logout").session(admin).with(csrf()))
                .andExpect(status().isOk());
        assertThat(userRepo.findByLoginName("rc3lockme").orElseThrow().getPasswordVersion())
                .isEqualTo(versionBefore + 1);
        assertThat(auditCount("FORCE_LOGOUT", targetId)).isEqualTo(1);
    }

    // ---------- 契约 3：反馈图片 ----------

    private MvcResult createFeedback(MockHttpSession s, String title, String imagesJson) throws Exception {
        return mvc.perform(post("/api/v1/feedback").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ISSUE\",\"title\":\"" + title + "\",\"description\":\"虚构描述\""
                                + (imagesJson == null ? "" : ",\"images\":" + imagesJson) + "}"))
                .andReturn();
    }

    private String imageJson(String fileName, String contentType, byte[] bytes) {
        return "[{\"fileName\":\"" + fileName + "\",\"contentType\":\"" + contentType
                + "\",\"contentBase64\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}]";
    }

    @Test
    void feedbackImages_visibility_headers_andUploadValidation() throws Exception {
        insertUser("rc3fadmin", "Admin#1234", true, false);
        MockHttpSession admin = loginOk("rc3fadmin", "Admin#1234");
        MockHttpSession author = register("rc3fauthor", "虚构反馈人");
        MockHttpSession other = register("rc3fother", "虚构围观人");

        MvcResult created = createFeedback(author, "虚构带图反馈", imageJson("shot.png", "image/png", PNG_BYTES));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String cb = created.getResponse().getContentAsString();
        long fid = ((Number) JsonPath.read(cb, "$.id")).longValue();
        long imageId = ((Number) JsonPath.read(cb, "$.images[0].id")).longValue();
        assertThat((Boolean) JsonPath.read(cb, "$.imagesVisible")).isTrue();

        // toMap：普通成员 imagesVisible=false，提交人 true
        MvcResult otherView = mvc.perform(get("/api/v1/feedback/" + fid).session(other))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(otherView.getResponse().getContentAsString(), "$.imagesVisible")).isFalse();

        String url = "/api/v1/feedback/" + fid + "/images/" + imageId;
        // 提交人 200 + 类型与三响应头
        MvcResult img = mvc.perform(get(url).session(author)).andExpect(status().isOk()).andReturn();
        assertThat(img.getResponse().getContentType()).contains("image/png");
        assertThat(img.getResponse().getHeader("Content-Disposition")).contains("inline");
        assertThat(img.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(img.getResponse().getHeader("Cache-Control")).contains("private");
        assertThat(img.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);
        // 管理员 200
        mvc.perform(get(url).session(admin)).andExpect(status().isOk());
        // 普通成员 403；未登录 401
        mvc.perform(get(url).session(other)).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        // 图片不属于该反馈 → 404
        MvcResult second = createFeedback(author, "虚构第二条反馈", null);
        long fid2 = ((Number) JsonPath.read(second.getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(get("/api/v1/feedback/" + fid2 + "/images/" + imageId).session(author))
                .andExpect(status().isNotFound());

        // 上传校验：SVG 被拒
        MvcResult svg = createFeedback(author, "虚构SVG反馈",
                imageJson("x.svg", "image/svg+xml", "<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes()));
        assertThat(svg.getResponse().getStatus()).isEqualTo(400);
        // 伪装 PNG 的 HTML 被拒
        MvcResult html = createFeedback(author, "虚构HTML反馈",
                imageJson("x.png", "image/png", "<html><body>hi</body></html>".getBytes()));
        assertThat(html.getResponse().getStatus()).isEqualTo(400);
        // 声明类型与魔数不符被拒（真 PNG 声明为 JPEG）
        MvcResult mismatch = createFeedback(author, "虚构不符反馈", imageJson("x.jpg", "image/jpeg", PNG_BYTES));
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(400);
        // 超 5MB 被拒
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length);
        MvcResult tooBig = createFeedback(author, "虚构大图反馈", imageJson("big.png", "image/png", big));
        assertThat(tooBig.getResponse().getStatus()).isEqualTo(400);
    }

    // ---------- 契约 4：通知字段 / 搜索 / resolution ----------

    @Test
    void notificationFields_searchFields_andResolution() throws Exception {
        MockHttpSession leader = register("rc3lead", "虚构组长甲");
        TeamCtx teamCtx = createTeam(leader, "虚构直达组", "RCRT");
        long teamId = teamCtx.teamId();
        long leaderId = myId(leader);
        MockHttpSession member = register("rc3mem", "虚构成员甲");
        joinDirect(member, teamCtx.invite());
        long memberId = myId(member);
        MockHttpSession outsider = register("rc3out", "虚构组外人");
        insertUser("rc3padmin", "Admin#1234", true, false);
        MockHttpSession padmin = loginOk("rc3padmin", "Admin#1234");

        long taskId = createItem(leader, teamId, "{\"type\":\"TASK\",\"title\":\"虚构直达任务\"}");
        long storyId = createItem(leader, teamId,
                "{\"type\":\"STORY\",\"title\":\"虚构直达故事\",\"parentId\":" + taskId + ",\"storyCategory\":\"DEV\"}");
        long adhocId = createItem(leader, teamId, "{\"type\":\"ADHOC\",\"title\":\"虚构直达临时事项\","
                + "\"destination\":\"ASSIGN\",\"ownerIds\":[" + leaderId + "]}");
        long iterationId = ((Number) JsonPath.read(mvc.perform(post("/api/v1/teams/" + teamId + "/iterations")
                        .session(leader).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"虚构迭代\",\"startDate\":\"2026-11-23\",\"endDate\":\"2026-12-04\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        jdbc.update("update item set iteration_id = ? where id = ?", iterationId, adhocId);
        entityManager.clear(); // jdbc 直改绕过 JPA 缓存

        // 通知 DTO 含 itemType/itemCode/teamId
        var task = itemRepo.findById(taskId).orElseThrow();
        var team = teamRepo.findById(teamId).orElseThrow();
        var memberUser = userRepo.findById(memberId).orElseThrow();
        Notification n = new Notification(memberUser, "ASSIGN", task, null, "虚构指派通知");
        n.setTeam(team);
        notificationRepo.save(n);
        MvcResult notifs = mvc.perform(get("/api/v1/notifications").session(member).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> entries = JsonPath.read(notifs.getResponse().getContentAsString(),
                "$[?(@.itemId==" + taskId + ")]");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("itemType")).isEqualTo("TASK");
        assertThat(entries.get(0).get("itemCode")).isEqualTo(task.getCode());
        assertThat(((Number) entries.get(0).get("teamId")).longValue()).isEqualTo(teamId);

        // 团队搜索结果含 type/code/teamId
        MvcResult search = mvc.perform(get("/api/v1/teams/" + teamId + "/search").session(member).param("q", "虚构直达任务"))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> hits = JsonPath.read(search.getResponse().getContentAsString(),
                "$[?(@.id==" + taskId + ")]");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("type")).isEqualTo("TASK");
        assertThat(hits.get(0).get("code")).isEqualTo(task.getCode());
        assertThat(((Number) hits.get(0).get("teamId")).longValue()).isEqualTo(teamId);

        // resolution：任务
        MvcResult rt = mvc.perform(get("/api/v1/items/" + taskId + "/resolution").session(member))
                .andExpect(status().isOk()).andReturn();
        String rtb = rt.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(rtb, "$.type")).isEqualTo("TASK");
        assertThat((String) JsonPath.read(rtb, "$.code")).isEqualTo(task.getCode());
        assertThat((String) JsonPath.read(rtb, "$.teamName")).isEqualTo("虚构直达组");
        assertThat((Boolean) JsonPath.read(rtb, "$.hasAccess")).isTrue();
        assertThat((Boolean) JsonPath.read(rtb, "$.deleted")).isFalse();
        assertThat((Object) JsonPath.read(rtb, "$.parentId")).isNull();

        // resolution：故事带 parentId
        MvcResult rs = mvc.perform(get("/api/v1/items/" + storyId + "/resolution").session(member))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(rs.getResponse().getContentAsString(), "$.type")).isEqualTo("STORY");
        assertThat(((Number) JsonPath.read(rs.getResponse().getContentAsString(), "$.parentId")).longValue())
                .isEqualTo(taskId);

        // resolution：独立事项带 iterationId
        MvcResult ra = mvc.perform(get("/api/v1/items/" + adhocId + "/resolution").session(member))
                .andExpect(status().isOk()).andReturn();
        assertThat((String) JsonPath.read(ra.getResponse().getContentAsString(), "$.type")).isEqualTo("ADHOC");
        assertThat(((Number) JsonPath.read(ra.getResponse().getContentAsString(), "$.iterationId")).longValue())
                .isEqualTo(iterationId);

        // 跨组非成员 hasAccess=false；平台管理员 true
        MvcResult ro = mvc.perform(get("/api/v1/items/" + taskId + "/resolution").session(outsider))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(ro.getResponse().getContentAsString(), "$.hasAccess")).isFalse();
        MvcResult rp = mvc.perform(get("/api/v1/items/" + taskId + "/resolution").session(padmin))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(rp.getResponse().getContentAsString(), "$.hasAccess")).isTrue();

        // 已删事项 deleted=true（仍可解析）
        jdbc.update("update item set deleted_at = now() where id = ?", adhocId);
        entityManager.clear();
        MvcResult rd = mvc.perform(get("/api/v1/items/" + adhocId + "/resolution").session(member))
                .andExpect(status().isOk()).andReturn();
        assertThat((Boolean) JsonPath.read(rd.getResponse().getContentAsString(), "$.deleted")).isTrue();

        // 不存在 404；未登录 401
        mvc.perform(get("/api/v1/items/99999999/resolution").session(member)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/items/" + taskId + "/resolution")).andExpect(status().isUnauthorized());
    }

    // ---------- 契约 4 追加：MENTION 通知带 refId（评论 id / 进展 id） ----------

    @Test
    void mentionNotifications_carryRefId_commentAndProgress() throws Exception {
        MockHttpSession leader = register("rc3mlead", "虚构提及组长");
        TeamCtx teamCtx = createTeam(leader, "虚构提及组", "RCMT");
        long teamId = teamCtx.teamId();
        long leaderId = myId(leader);
        MockHttpSession member = register("rc3mmem", "虚构被提及成员");
        joinDirect(member, teamCtx.invite());
        long memberId = myId(member);

        // 评论 @（用任务，避免与进展 @ 的同事项合并逻辑互相覆盖）
        long taskId = createItem(leader, teamId, "{\"type\":\"TASK\",\"title\":\"虚构提及任务\"}");
        MvcResult comment = mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + taskId + "/comments")
                        .session(leader).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"虚构评论请看一下\",\"mentionIds\":[" + memberId + "]}"))
                .andExpect(status().isCreated()).andReturn();
        long commentId = ((Number) JsonPath.read(comment.getResponse().getContentAsString(), "$.id")).longValue();

        // 进展 @（用独立事项，负责人为组长本人）
        long adhocId = createItem(leader, teamId, "{\"type\":\"ADHOC\",\"title\":\"虚构提及临时事项\","
                + "\"destination\":\"ASSIGN\",\"ownerIds\":[" + leaderId + "]}");
        MvcResult progress = mvc.perform(post("/api/v1/teams/" + teamId + "/items/" + adhocId + "/progress")
                        .session(leader).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"虚构进展同步\",\"mentionIds\":[" + memberId + "]}"))
                .andExpect(status().isCreated()).andReturn();
        long progressId = ((Number) JsonPath.read(progress.getResponse().getContentAsString(), "$.id")).longValue();

        var mentions = notificationRepo.findByRecipientId(memberId).stream()
                .filter(n -> "MENTION".equals(n.getType())).toList();
        assertThat(mentions).hasSize(2);
        var byItem = new java.util.HashMap<Long, Long>();
        mentions.forEach(n -> byItem.put(n.getItem().getId(), n.getRefId()));
        assertThat(byItem.get(taskId)).isEqualTo(commentId);
        assertThat(byItem.get(adhocId)).isEqualTo(progressId);

        // 通知 DTO 同步带出 refId
        MvcResult notifs = mvc.perform(get("/api/v1/notifications").session(member).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> dtoMentions = JsonPath.read(notifs.getResponse().getContentAsString(),
                "$[?(@.type=='MENTION')]");
        assertThat(dtoMentions.stream().map(m -> ((Number) m.get("refId")).longValue()))
                .containsExactlyInAnyOrder(commentId, progressId);
    }
}
