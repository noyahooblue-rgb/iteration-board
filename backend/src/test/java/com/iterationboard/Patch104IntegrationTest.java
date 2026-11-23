package com.iterationboard;

import com.iterationboard.domain.AppUser;
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 1.0.4 回归测试：邀请认领通知、创建直指派/验收人通知、认领通知、进展 @ 提及、
 * 评论通知负责人、加入申请与审批结果通知、平台管理员只读接口。
 * 全部测试数据为虚构账号与事项。MockMvc + 真实 PostgreSQL，事务回滚隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Patch104IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepo userRepo;

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

    private TeamCtx createTeam(MockHttpSession session, String name, String code, boolean joinApproval) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"code\":\"" + code + "\",\"joinApproval\":" + joinApproval + "}"))
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

    private String notificationsJson(MockHttpSession s, long teamId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/notifications").session(s).param("teamId", String.valueOf(teamId)))
                .andExpect(status().isOk()).andReturn();
        return r.getResponse().getContentAsString();
    }

    private int itemVersion(MockHttpSession s, long teamId, long itemId) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/teams/" + teamId + "/items/" + itemId).session(s))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.version")).intValue();
    }

    // ---------- 1. 邀请认领必须发通知（本轮缺陷 1 的根因回归） ----------

    @Test
    void inviteCreate_notifiesInvitee() throws Exception {
        MockHttpSession leader = register("p104alead", "虚构组长甲");
        TeamCtx team = createTeam(leader, "虚构邀请组", "PIA", false);
        MockHttpSession member = register("p104amem", "虚构成员甲");
        long memberId = myId(member);
        joinDirect(member, team.invite());

        createItem(leader, team.teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构邀请事项\",\"destination\":\"INVITE\",\"inviteeIds\":[" + memberId + "]}");

        String notifs = notificationsJson(member, team.teamId());
        assertThat(notifs).contains("\"type\":\"INVITE\"");
        assertThat(notifs).contains("\"category\":\"ASSIGNMENT\"");
        // 创建人本人不应收到邀请通知
        assertThat(notificationsJson(leader, team.teamId())).doesNotContain("\"type\":\"INVITE\"");
    }

    // ---------- 2. 创建时直指派负责人 + 设定验收人都要通知 ----------

    @Test
    void assignCreate_notifiesOwnerAndAcceptor() throws Exception {
        MockHttpSession leader = register("p104blead", "虚构组长乙");
        TeamCtx team = createTeam(leader, "虚构指派组", "PIB", false);
        MockHttpSession owner = register("p104bown", "虚构负责人乙");
        long ownerId = myId(owner);
        joinDirect(owner, team.invite());
        MockHttpSession acceptor = register("p104bacc", "虚构验收人乙");
        long acceptorId = myId(acceptor);
        joinDirect(acceptor, team.invite());

        createItem(leader, team.teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构指派事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + ownerId
                        + "],\"acceptorId\":" + acceptorId + "}");

        assertThat(notificationsJson(owner, team.teamId())).contains("\"type\":\"ASSIGN\"");
        assertThat(notificationsJson(acceptor, team.teamId())).contains("\"type\":\"ACCEPTOR\"");
    }

    // ---------- 3. 认领后通知创建人；评论通知负责人 ----------

    @Test
    void claimAndComment_notifyCreatorAndOwner() throws Exception {
        MockHttpSession leader = register("p104clead", "虚构组长丙");
        TeamCtx team = createTeam(leader, "虚构认领组", "PIC", false);
        MockHttpSession member = register("p104cmem", "虚构成员丙");
        joinDirect(member, team.invite());
        MockHttpSession other = register("p104coth", "虚构同事丙");
        joinDirect(other, team.invite());

        long itemId = createItem(leader, team.teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构池事项\",\"destination\":\"POOL\"}");
        int v = itemVersion(member, team.teamId(), itemId);
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/items/" + itemId + "/transitions").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CLAIM\",\"version\":" + v + "}"))
                .andExpect(status().isOk());
        assertThat(notificationsJson(leader, team.teamId())).contains("\"type\":\"CLAIM\"");

        // 负责人（member）负责的事项被他人评论 → 负责人收到 COMMENT；评论者本人不收
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/items/" + itemId + "/comments").session(other).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"虚构评论内容\"}"))
                .andExpect(status().isCreated());
        assertThat(notificationsJson(member, team.teamId())).contains("\"type\":\"COMMENT\"");
        assertThat(notificationsJson(other, team.teamId())).doesNotContain("\"type\":\"COMMENT\"");
    }

    // ---------- 4. 进展 @ 提及：落库 + 通知 + DTO 输出 ----------

    @Test
    void progressMention_notifiesAndRoundTrips() throws Exception {
        MockHttpSession leader = register("p104dlead", "虚构组长丁");
        long leaderId = myId(leader);
        TeamCtx team = createTeam(leader, "虚构进展组", "PID", false);
        MockHttpSession member = register("p104dmem", "虚构成员丁");
        long memberId = myId(member);
        joinDirect(member, team.invite());

        long itemId = createItem(leader, team.teamId(),
                "{\"type\":\"ADHOC\",\"title\":\"虚构进展事项\",\"destination\":\"ASSIGN\",\"ownerIds\":[" + memberId + "]}");

        MvcResult r = mvc.perform(post("/api/v1/teams/" + team.teamId() + "/items/" + itemId + "/progress")
                        .session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"虚构进展说明\",\"percent\":30,\"mentionIds\":[" + leaderId + "]}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("\"mentionIds\":[" + leaderId + "]");

        assertThat(notificationsJson(leader, team.teamId())).contains("\"type\":\"MENTION\"");
        assertThat(notificationsJson(leader, team.teamId())).contains("有人在进展里 @ 了你");

        MvcResult list = mvc.perform(get("/api/v1/teams/" + team.teamId() + "/items/" + itemId + "/progress-list")
                        .session(leader))
                .andExpect(status().isOk()).andReturn();
        assertThat(list.getResponse().getContentAsString()).contains("\"mentionIds\":[" + leaderId + "]");
    }

    // ---------- 5. 加入申请：提交通知组长，审批结果通知申请人 ----------

    @Test
    void joinRequest_notifiesLeaderAndApplicant() throws Exception {
        MockHttpSession leader = register("p104elead", "虚构组长戊");
        TeamCtx team = createTeam(leader, "虚构审批组", "PIE", true);
        MockHttpSession applicant = register("p104eapp", "虚构申请人戊");

        mvc.perform(post("/api/v1/teams/join").session(applicant).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + team.invite() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(false));
        assertThat(notificationsJson(leader, team.teamId())).contains("\"type\":\"JOIN_REQUEST\"");

        MvcResult pending = mvc.perform(get("/api/v1/teams/" + team.teamId() + "/join-requests").session(leader))
                .andExpect(status().isOk()).andReturn();
        long requestId = ((Number) JsonPath.read(pending.getResponse().getContentAsString(), "$[0].id")).longValue();
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/join-requests/" + requestId + "/approve")
                        .session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
        // 申请人的通知不带小组过滤也可查（申请通过通知带 team 维度）
        assertThat(notificationsJson(applicant, team.teamId())).contains("\"type\":\"JOIN_APPROVED\"");

        // 拒绝路径：第二名申请人被拒后收到 JOIN_REJECTED
        MockHttpSession applicant2 = register("p104eap2", "虚构申请人戊二");
        mvc.perform(post("/api/v1/teams/join").session(applicant2).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + team.invite() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(false));
        MvcResult pending2 = mvc.perform(get("/api/v1/teams/" + team.teamId() + "/join-requests").session(leader))
                .andExpect(status().isOk()).andReturn();
        long requestId2 = ((Number) JsonPath.read(pending2.getResponse().getContentAsString(), "$[0].id")).longValue();
        mvc.perform(post("/api/v1/teams/" + team.teamId() + "/join-requests/" + requestId2 + "/reject")
                        .session(leader).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(notificationsJson(applicant2, team.teamId())).contains("\"type\":\"JOIN_REJECTED\"");
    }

    // ---------- 6. 平台管理员只读接口：非管理员 403，管理员 200 且字段齐全 ----------

    @Test
    void adminEndpoints_requirePlatformAdmin() throws Exception {
        MockHttpSession plain = register("p104fusr", "虚构普通用户");
        // 显式钉死非管理员：注册规则是「库中第一个用户自动成为平台管理员」，干净库下本用例的第一个注册者会被自动升格，
        // 不钉死则 403 断言取决于库里是否已有其他用户（与管理员用例显式 setPlatformAdmin(true) 对称处理）。
        AppUser plainUser = userRepo.findById(myId(plain)).orElseThrow();
        plainUser.setPlatformAdmin(false);
        userRepo.save(plainUser);
        mvc.perform(get("/api/v1/admin/users").session(plain)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/teams").session(plain)).andExpect(status().isForbidden());

        MockHttpSession adminS = register("p104fadm", "虚构平台管理员");
        long adminId = myId(adminS);
        AppUser admin = userRepo.findById(adminId).orElseThrow();
        admin.setPlatformAdmin(true);
        userRepo.save(admin);
        createTeam(adminS, "虚构管理组", "PIF", false);

        MvcResult users = mvc.perform(get("/api/v1/admin/users").session(adminS))
                .andExpect(status().isOk()).andReturn();
        String usersBody = users.getResponse().getContentAsString();
        assertThat(usersBody).contains("\"loginName\":\"p104fadm\"");
        assertThat(usersBody).contains("\"platformAdmin\":true");
        assertThat(usersBody).contains("\"displayName\":\"虚构普通用户\"");

        MvcResult teams = mvc.perform(get("/api/v1/admin/teams").session(adminS))
                .andExpect(status().isOk()).andReturn();
        String teamsBody = teams.getResponse().getContentAsString();
        assertThat(teamsBody).contains("\"name\":\"虚构管理组\"");
        assertThat(teamsBody).contains("\"code\":\"PIF\"");
        assertThat(teamsBody).contains("\"memberCount\":1");
    }
}
