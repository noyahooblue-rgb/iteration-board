package com.iterationboard;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修订二场景测试发现的真实缺陷回归：GET /teams/{id}/join-requests 在生产路径（无测试事务绑定会话）下
 * 因 JoinRequest.user 为 LAZY 关联、DTO 映射发生在控制器（事务外）而 500（LazyInitializationException）。
 * 本类故意不加 @Transactional——其余集成测试的测试事务会掩盖该问题；数据用 jr_ 前缀并在 AfterEach 清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JoinRequestProductionPathTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM join_request WHERE user_id IN (SELECT id FROM app_user WHERE login_name LIKE 'jr_%')");
        // 1.0.4 起加入申请/审批会生成带 team_id 的通知，必须先于 team/app_user 删除，否则 FK 违例导致清理中断、残留数据污染后续用例
        jdbc.update("DELETE FROM notification WHERE team_id IN (SELECT id FROM team WHERE code = 'JRT')");
        jdbc.update("DELETE FROM notification WHERE recipient_id IN (SELECT id FROM app_user WHERE login_name LIKE 'jr_%') OR actor_id IN (SELECT id FROM app_user WHERE login_name LIKE 'jr_%')");
        jdbc.update("DELETE FROM audit_log WHERE team_id IN (SELECT id FROM team WHERE code = 'JRT')");
        jdbc.update("DELETE FROM team_member WHERE user_id IN (SELECT id FROM app_user WHERE login_name LIKE 'jr_%')");
        jdbc.update("DELETE FROM team_member WHERE team_id IN (SELECT id FROM team WHERE code = 'JRT')");
        jdbc.update("DELETE FROM team WHERE code = 'JRT'");
        jdbc.update("DELETE FROM app_user WHERE login_name LIKE 'jr_%'");
    }

    private String[] captcha(MockHttpSession s) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/captcha").session(s)).andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        Matcher m = Pattern.compile("<text[^>]*>(.)</text>").matcher((String) JsonPath.read(body, "$.imageSvg"));
        StringBuilder code = new StringBuilder();
        while (m.find()) code.append(m.group(1));
        return new String[]{JsonPath.read(body, "$.captchaId"), code.toString()};
    }

    private MockHttpSession register(String login) throws Exception {
        MockHttpSession s = new MockHttpSession();
        String[] c = captcha(s);
        mvc.perform(post("/api/v1/auth/register").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + login + "\",\"displayName\":\"" + login + "\",\"password\":\"passw0rd1!\",\"captchaId\":\"" + c[0] + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        return s;
    }

    @Test
    void pendingJoinRequests_readableOutsideTestTransaction() throws Exception {
        MockHttpSession leader = register("jr_leader");
        MvcResult created = mvc.perform(post("/api/v1/teams").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"回归组\",\"code\":\"JRT\",\"joinApproval\":true}"))
                .andExpect(status().isCreated()).andReturn();
        String teamBody = created.getResponse().getContentAsString();
        long teamId = ((Number) JsonPath.read(teamBody, "$.id")).longValue();
        String invite = JsonPath.read(teamBody, "$.inviteCode");

        MockHttpSession applicant = register("jr_applicant");
        mvc.perform(post("/api/v1/teams/join").session(applicant).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk());

        // 修复前此处 500（LazyInitializationException）；修复后 200 且含申请人
        MvcResult pending = mvc.perform(get("/api/v1/teams/" + teamId + "/join-requests").session(leader))
                .andExpect(status().isOk()).andReturn();
        String body = pending.getResponse().getContentAsString();
        assertThat(body).contains("jr_applicant");

        // 同类缺陷连带点（场景测试实测 500）：/teams/mine 的 TeamDto 映射、审批后 /members 与成员状态 PATCH 的 MemberDto 映射
        MvcResult mine = mvc.perform(get("/api/v1/teams/mine").session(leader))
                .andExpect(status().isOk()).andReturn();
        assertThat(mine.getResponse().getContentAsString()).contains("回归组");

        long requestId = ((Number) JsonPath.read(body, "$[0].id")).longValue();
        mvc.perform(post("/api/v1/teams/" + teamId + "/join-requests/" + requestId + "/approve").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isNoContent());
        MvcResult members = mvc.perform(get("/api/v1/teams/" + teamId + "/members").session(leader))
                .andExpect(status().isOk()).andReturn();
        assertThat(members.getResponse().getContentAsString()).contains("jr_applicant");

        long applicantId = ((Number) JsonPath.read(members.getResponse().getContentAsString(), "$[1].userId")).longValue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/teams/" + teamId + "/members/" + applicantId + "/status").session(leader).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberStatus\":\"LEAVE\",\"memberStatusStart\":\"2026-11-17\",\"memberStatusEnd\":\"2026-11-19\"}"))
                .andExpect(status().isOk());
    }
}
