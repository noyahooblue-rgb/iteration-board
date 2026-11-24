package com.iterationboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 1.0.3（实测第四轮）回归测试：
 * - 未登录 GET / 返回 200 HTML（前端入口，不再是 401 JSON）；
 * - 未登录 GET /board（前端路由）经 SPA 回退返回 index.html；
 * - 未登录 GET /api/v1/teams 返回 401 JSON 且中文不乱码（UTF-8）；
 * - 带扩展名的未知静态路径仍是 404，不被回退吞掉。
 * 测试 classpath 里放了一份标记 index.html（src/test/resources/static/），
 * 生产 jar 的 index.html 由打包阶段嵌入，内容不同、路径口径相同。
 *
 * rc4（方案 A）追加：SPA 兜底改由 WebConfig 的资源解析器承担（原 SpaController 显式清单删除）——
 * - 未在旧清单内的新前端路由 /i/:id（事项直达）、/change-password（独立改密页）未登录也回退 index.html；
 * - 已登录 GET 不存在的 API 路径仍是后端真 404 JSON（code=NOT_FOUND），不被兜底吞成首页。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpaFallbackSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String GOOD_PW = "passw0rd1!";

    /** 直接造数 + 登录（口径同 Rc3IntegrationTest 的 insertUser/loginOk）。 */
    private MockHttpSession loginOk(String loginName) throws Exception {
        jdbc.update("insert into app_user (login_name, display_name, password_hash, avatar_color, platform_admin, status,"
                        + " must_change_password, failed_attempts, password_version, created_at, updated_at)"
                        + " values (?, ?, ?, 'indigo', false, 'ACTIVE', false, 0, 0, now(), now())",
                loginName, "虚构-" + loginName, passwordEncoder.encode(GOOD_PW));
        MockHttpSession session = new MockHttpSession();
        var r = mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"password\":\"" + GOOD_PW + "\"}"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("登录应成功: %s", loginName).isEqualTo(200);
        return session;
    }

    @Test
    void rootServesIndexHtmlWithoutLogin() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("TEST-INDEX-MARKER")));
    }

    @Test
    void spaRouteFallsBackToIndexHtml() throws Exception {
        mvc.perform(get("/board"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("TEST-INDEX-MARKER")));
        mvc.perform(get("/workbench"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TEST-INDEX-MARKER")));
        // 1.0.4 新增前端路由 /admin（平台管理）也必须在回退清单内，
        // 否则书签直开/刷新会拿到 JSON 404（真机实测抓到）。
        mvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TEST-INDEX-MARKER")));
        // 1.1.0 新增前端路由 /feedback（意见反馈）同理必须在回退清单内。
        mvc.perform(get("/feedback"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TEST-INDEX-MARKER")));
    }

    @Test
    void apiStillRequiresAuthAndReturnsUtf8Json() throws Exception {
        mvc.perform(get("/api/v1/teams"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("未登录或会话已过期")));
    }

    @Test
    void unknownStaticFileIs404NotFallback() throws Exception {
        mvc.perform(get("/assets/does-not-exist.css"))
                .andExpect(status().isNotFound());
    }

    /** rc4 方案 A：旧 SpaController 清单里没有的新路由，靠通用兜底回退（这正是 rc3 真机 404 的根因场景）。 */
    @Test
    void rc4NewSpaRoutesFallBackToIndexHtml() throws Exception {
        mvc.perform(get("/i/123"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("TEST-INDEX-MARKER")));
        mvc.perform(get("/change-password"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("TEST-INDEX-MARKER")));
    }

    /** rc4 方案 A 安全网：兜底不得吞掉 API 路径——不存在的接口已登录时仍是真 404 JSON。 */
    @Test
    void unknownApiRouteIs404JsonNotFallback() throws Exception {
        MockHttpSession session = loginOk("spafallbackuser");
        mvc.perform(get("/api/v1/definitely-not-a-route").session(session))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
