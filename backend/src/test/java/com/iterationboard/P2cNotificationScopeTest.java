package com.iterationboard;

import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Notification;
import com.iterationboard.domain.Team;
import com.iterationboard.repo.NotificationRepo;
import com.iterationboard.repo.TeamRepo;
import com.iterationboard.repo.UserRepo;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P2c 裁定（zu5skyzg，A+C 合并）后端覆盖：
 * - scope=current（当前团队 + 平台通知）包含 team_id 为空的通知、排除其他团队；
 * - 角标口径恒等：unread-count?teamId == scope=current 的 unreadCount；
 * - unread-counts-by-team 各团队/平台分开计数；
 * - read-all 只作用于当前视图；
 * - 反馈状态通知带 refId（反馈 id）且在 scope=current 中可见；
 * - 反馈搜索只返回本人提交的反馈。
 * MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class P2cNotificationScopeTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepo notificationRepo;
    @Autowired UserRepo userRepo;
    @Autowired TeamRepo teamRepo;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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

    private long createTeam(MockHttpSession session, String name, String code) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"joinApproval\":false}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private MockHttpSession bootstrapAdmin() throws Exception {
        // rc2 起 HTTP 引导接口已删除：直接在库中种入 admin（建号走应用内 Runner）
        AppUser adminUser = new AppUser();
        adminUser.setLoginName("admin");
        adminUser.setDisplayName("平台管理员");
        adminUser.setPasswordHash(passwordEncoder.encode("InitPass1234"));
        adminUser.setAvatarColor("indigo");
        adminUser.setPlatformAdmin(true);
        adminUser.setStatus("ACTIVE");
        adminUser.setMustChangePassword(true);
        userRepo.save(adminUser);
        MockHttpSession admin = login("admin", "InitPass1234");
        mvc.perform(post("/api/v1/auth/change-password").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"InitPass1234\",\"newPassword\":\"NewPass1234!\"}"))
                .andExpect(status().isNoContent());
        return admin;
    }

    private Notification insert(AppUser recipient, Team team, String type, boolean read,
                                Instant createdAt, Long refId) {
        Notification n = new Notification(recipient, type, null, null, "测试通知-" + type);
        n.setTeam(team);
        n.setCreatedAt(createdAt);
        if (read) n.setReadAt(createdAt.plus(1, ChronoUnit.MINUTES));
        n.setRefId(refId);
        return notificationRepo.save(n);
    }

    private String scopeCurrentJson(MockHttpSession s, long teamId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/notifications").session(s)
                        .param("scope", "current").param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    // ---------- 测试 ----------

    @Test
    void scopeCurrent_includesPlatform_excludesOtherTeam_mixedByTime() throws Exception {
        MockHttpSession u = register("p2c_user1", "当前视图用户");
        AppUser me = userRepo.findById(myId(u)).orElseThrow();
        long teamA = createTeam(u, "P2c甲组", "PA");
        long teamB = createTeam(u, "P2c乙组", "PB");
        Team a = teamRepo.findById(teamA).orElseThrow();
        Team b = teamRepo.findById(teamB).orElseThrow();
        Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        Notification nTeamOld = insert(me, a, "ASSIGN", false, t0, null);
        Notification nPlatform = insert(me, null, "FEEDBACK_STATUS", false, t0.plus(10, ChronoUnit.MINUTES), 42L);
        Notification nTeamRead = insert(me, a, "COMMENT", true, t0.plus(20, ChronoUnit.MINUTES), null);
        Notification nOtherTeam = insert(me, b, "ASSIGN", false, t0.plus(30, ChronoUnit.MINUTES), null);
        Notification nPlatform2 = insert(me, null, "SYSTEM", false, t0.plus(40, ChronoUnit.MINUTES), null);

        String body = scopeCurrentJson(u, teamA);
        List<Map<String, Object>> items = JsonPath.read(body, "$.items");
        List<Long> ids = items.stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        // 当前团队 + 平台都在，其他团队排除；按时间倒序混排
        assertThat(ids).containsExactly(nPlatform2.getId(), nTeamRead.getId(), nPlatform.getId(), nTeamOld.getId());
        assertThat(ids).doesNotContain(nOtherTeam.getId());
        // 平台通知 teamId 为空且带 refId
        Map<String, Object> platformRow = items.stream()
                .filter(m -> ((Number) m.get("id")).longValue() == nPlatform.getId()).findFirst().orElseThrow();
        assertThat(platformRow.get("teamId")).isNull();
        assertThat(((Number) platformRow.get("refId")).longValue()).isEqualTo(42L);
        // 未读数 = 当前视图内未读（团队 1 + 平台 2），已读不计
        assertThat(((Number) JsonPath.read(body, "$.unreadCount")).longValue()).isEqualTo(3L);
    }

    @Test
    void unreadCount_equalsScopeCurrent_andByTeamBreakdown() throws Exception {
        MockHttpSession u = register("p2c_user2", "计数用户");
        AppUser me = userRepo.findById(myId(u)).orElseThrow();
        long teamA = createTeam(u, "P2c丙组", "PC");
        long teamB = createTeam(u, "P2c丁组", "PD");
        Team a = teamRepo.findById(teamA).orElseThrow();
        Team b = teamRepo.findById(teamB).orElseThrow();
        Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        insert(me, a, "ASSIGN", false, t0, null);
        insert(me, a, "COMMENT", true, t0.plus(1, ChronoUnit.MINUTES), null);
        insert(me, null, "FEEDBACK_STATUS", false, t0.plus(2, ChronoUnit.MINUTES), 7L);
        insert(me, b, "ASSIGN", false, t0.plus(3, ChronoUnit.MINUTES), null);
        insert(me, b, "MENTION", false, t0.plus(4, ChronoUnit.MINUTES), null);

        String scopeBody = scopeCurrentJson(u, teamA);
        long scopeUnread = ((Number) JsonPath.read(scopeBody, "$.unreadCount")).longValue();
        MvcResult uc = mvc.perform(get("/api/v1/notifications/unread-count").session(u)
                        .param("teamId", String.valueOf(teamA)))
                .andExpect(status().isOk()).andReturn();
        long badge = ((Number) JsonPath.read(uc.getResponse().getContentAsString(), "$.unread")).longValue();
        // 恒等：角标 == 面板默认视图未读（团队 A 未读 1 + 平台未读 1）
        assertThat(badge).isEqualTo(scopeUnread).isEqualTo(2L);

        MvcResult bt = mvc.perform(get("/api/v1/notifications/unread-counts-by-team").session(u))
                .andExpect(status().isOk()).andReturn();
        String btBody = bt.getResponse().getContentAsString();
        Map<String, Object> teams = JsonPath.read(btBody, "$.teams");
        assertThat(((Number) teams.get(String.valueOf(teamA))).longValue()).isEqualTo(1L);
        assertThat(((Number) teams.get(String.valueOf(teamB))).longValue()).isEqualTo(2L);
        assertThat(((Number) JsonPath.read(btBody, "$.platform")).longValue()).isEqualTo(1L);
    }

    @Test
    void readAll_onlyCurrentView() throws Exception {
        MockHttpSession u = register("p2c_user3", "已读用户");
        AppUser me = userRepo.findById(myId(u)).orElseThrow();
        long teamA = createTeam(u, "P2c戊组", "PE");
        long teamB = createTeam(u, "P2c己组", "PF");
        Team a = teamRepo.findById(teamA).orElseThrow();
        Team b = teamRepo.findById(teamB).orElseThrow();
        Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        insert(me, a, "ASSIGN", false, t0, null);
        insert(me, null, "FEEDBACK_COMMENT", false, t0.plus(1, ChronoUnit.MINUTES), 9L);
        Notification otherTeam = insert(me, b, "ASSIGN", false, t0.plus(2, ChronoUnit.MINUTES), null);

        MvcResult r = mvc.perform(post("/api/v1/notifications/read-all").session(u).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + teamA + "}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.marked")).longValue()).isEqualTo(2L);

        String after = scopeCurrentJson(u, teamA);
        assertThat(((Number) JsonPath.read(after, "$.unreadCount")).longValue()).isEqualTo(0L);
        // 其他团队的未读不受影响
        assertThat(notificationRepo.findById(otherTeam.getId()).orElseThrow().getReadAt()).isNull();
        MvcResult bt = mvc.perform(get("/api/v1/notifications/unread-counts-by-team").session(u))
                .andExpect(status().isOk()).andReturn();
        Map<String, Object> teams = JsonPath.read(bt.getResponse().getContentAsString(), "$.teams");
        assertThat(((Number) teams.get(String.valueOf(teamB))).longValue()).isEqualTo(1L);
    }

    @Test
    void scopeCurrent_requiresTeamId() throws Exception {
        MockHttpSession u = register("p2c_user4", "缺参用户");
        mvc.perform(get("/api/v1/notifications").session(u).param("scope", "current"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feedbackStatusNotification_carriesRefId_visibleInScopeCurrent() throws Exception {
        MockHttpSession author = register("p2c_author", "反馈作者");
        long teamId = createTeam(author, "P2c庚组", "PG");
        MvcResult created = mvc.perform(post("/api/v1/feedback").session(author).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SUGGESTION\",\"title\":\"P2c裁定验证反馈\",\"description\":\"验证平台通知进当前视图\"}"))
                .andExpect(status().isCreated()).andReturn();
        long fid = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

        MockHttpSession admin = bootstrapAdmin();
        mvc.perform(patch("/api/v1/feedback/" + fid + "/status").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ANALYZING\"}"))
                .andExpect(status().isOk());

        String body = scopeCurrentJson(author, teamId);
        List<Map<String, Object>> items = JsonPath.read(body, "$.items");
        Map<String, Object> fbNotif = items.stream()
                .filter(m -> "FEEDBACK_STATUS".equals(m.get("type"))).findFirst()
                .orElseThrow(() -> new AssertionError("scope=current 中没有 FEEDBACK_STATUS 平台通知: " + body));
        assertThat(fbNotif.get("teamId")).isNull();
        assertThat(((Number) fbNotif.get("refId")).longValue()).isEqualTo(fid);
        assertThat(((Number) JsonPath.read(body, "$.unreadCount")).longValue()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void feedbackSearch_returnsOnlyOwn() throws Exception {
        MockHttpSession u1 = register("p2c_seek1", "搜索甲");
        MockHttpSession u2 = register("p2c_seek2", "搜索乙");
        String kw = "甘蔗关键词";
        mvc.perform(post("/api/v1/feedback").session(u1).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ISSUE\",\"title\":\"甲的" + kw + "反馈\",\"description\":\"甲提交\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/feedback").session(u2).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ISSUE\",\"title\":\"乙的" + kw + "反馈\",\"description\":\"乙提交\"}"))
                .andExpect(status().isCreated());

        MvcResult r = mvc.perform(get("/api/v1/feedback/search").session(u1).param("q", kw))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> hits = JsonPath.read(r.getResponse().getContentAsString(), "$");
        assertThat(hits).hasSize(1);
        assertThat((String) hits.get(0).get("title")).contains("甲的");
    }
}
