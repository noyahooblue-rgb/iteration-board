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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 安全回归（1.1.0-rc2，裁定 hsa43amn 一.3）：堵住「免登录管理员接口」同类问题复发。
 * - 白名单断言：SecurityConfig 中 permitAll 的 /api 路径必须严格等于
 *   {meta, auth/captcha, auth/register, auth/login}；以后新增任何免登录接口，
 *   必须先改这个测试——相当于强制评审。
 * - /api/v1/admin/** ：未登录一律 401，普通成员一律 403（已删除的 bootstrap/reset-admin 旧路径同断言）。
 * - 接口文档路径（springdoc 默认关闭）：未登录 401、普通成员 403，仅平台管理员可达。
 * MockMvc + 真实 PostgreSQL，事务回滚隔离；全部测试数据为虚构账号。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminEndpointSecurityTest {

    @Autowired MockMvc mvc;

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
                                + "\",\"password\":\"passw0rd1!\",\"captchaId\":\"" + c[0]
                                + "\",\"captchaCode\":\"" + c[1] + "\"}"))
                .andExpect(status().isCreated());
        return session;
    }

    // ---------- 白名单 ----------

    @Test
    void permitAllWhitelist_exactlyFourApiPaths() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/iterationboard/config/SecurityConfig.java"));
        Set<String> apiPaths = new TreeSet<>();
        Matcher groups = Pattern.compile("requestMatchers\\(([^)]*)\\)\\s*\\.permitAll\\(\\)").matcher(src);
        while (groups.find()) {
            Matcher lit = Pattern.compile("\"(/[^\"]*)\"").matcher(groups.group(1));
            while (lit.find()) {
                if (lit.group(1).startsWith("/api/")) apiPaths.add(lit.group(1));
            }
        }
        assertThat(apiPaths).containsExactlyInAnyOrder(
                "/api/v1/meta", "/api/v1/auth/captcha", "/api/v1/auth/register", "/api/v1/auth/login");
    }

    // ---------- /api/v1/admin/** ----------

    @Test
    void adminPaths_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/v1/admin/backup-status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/system-info")).andExpect(status().isUnauthorized());
        // 已删除的旧接口同样必须 401（不得以任何形式复活为免登录入口）
        mvc.perform(get("/api/v1/admin/bootstrap/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/bootstrap").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"evil\",\"password\":\"Whatever123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/reset-admin").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"admin\",\"temporaryPassword\":\"Whatever123\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/admin/users/1/platform-admin").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platformAdmin\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/admin/users/1").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminPaths_ordinaryMember_403() throws Exception {
        MockHttpSession member = register("secmember", "虚构普通成员");
        mvc.perform(get("/api/v1/admin/backup-status").session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/system-info").session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/bootstrap/status").session(member)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/bootstrap").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"evil\",\"password\":\"Whatever123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reset-admin").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"admin\",\"temporaryPassword\":\"Whatever123\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/users/1/platform-admin").session(member).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platformAdmin\":true}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 接口文档（默认关闭；开启也仅平台管理员） ----------

    @Test
    void apiDocsPaths_requirePlatformAdmin() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/openapi.yaml")).andExpect(status().isUnauthorized());
        MockHttpSession member = register("secdoc", "虚构普通成员乙");
        mvc.perform(get("/v3/api-docs").session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/swagger-ui/index.html").session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/openapi.yaml").session(member)).andExpect(status().isForbidden());
    }
}
