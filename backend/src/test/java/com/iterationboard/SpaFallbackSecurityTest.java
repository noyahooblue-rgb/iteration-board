package com.iterationboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 1.0.3（实测第四轮）回归测试：
 * - 未登录 GET / 返回 200 HTML（前端入口，不再是 401 JSON）；
 * - 未登录 GET /board（前端路由）经 SPA 回退返回 index.html；
 * - 未登录 GET /api/v1/teams 返回 401 JSON 且中文不乱码（UTF-8）；
 * - 带扩展名的未知静态路径仍是 404，不被回退吞掉。
 * 测试 classpath 里放了一份标记 index.html（src/test/resources/static/），
 * 生产 jar 的 index.html 由打包阶段嵌入，内容不同、路径口径相同。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpaFallbackSecurityTest {

    @Autowired MockMvc mvc;

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
}
