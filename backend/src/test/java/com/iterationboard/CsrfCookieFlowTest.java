package com.iterationboard;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 浏览器口径 CSRF 回归（M3 jar 端到端冒烟发现）：
 * 真实前端按契约 0.2.0 把 XSRF-TOKEN Cookie 的原值放进 X-XSRF-TOKEN 请求头提交。
 * 本测试不用 csrf() 后处理器，全程模拟浏览器：先 GET 验证码拿到 XSRF-TOKEN Cookie，
 * 再带着 Cookie + 同值请求头 POST 注册——修复前该请求 403（默认 XOR 掩码处理器不接受原值），
 * 修复后（plain CsrfTokenRequestAttributeHandler）应 201。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CsrfCookieFlowTest {

    @Autowired MockMvc mvc;

    /** 与浏览器一致的引导：首访先 GET 验证码页，响应就应下发 XSRF-TOKEN Cookie（令牌物化过滤器）。 */
    private Cookie bootstrapXsrfCookie() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/auth/captcha")).andReturn();
        Cookie xsrf = r.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("首访 GET 验证码的响应应下发 XSRF-TOKEN Cookie").isNotNull();
        return xsrf;
    }

    @Test
    void browserStyleCookieCsrfTokenIsAccepted() throws Exception {
        Cookie xsrf = bootstrapXsrfCookie();
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        MvcResult cap = mvc.perform(get("/api/v1/auth/captcha").session(session).cookie(xsrf))
                .andExpect(status().isOk()).andReturn();
        String body = cap.getResponse().getContentAsString();
        String captchaId = JsonPath.read(body, "$.captchaId");
        String svg = JsonPath.read(body, "$.imageSvg");
        Matcher m = Pattern.compile("<text[^>]*>(.)</text>").matcher(svg);
        StringBuilder code = new StringBuilder();
        while (m.find()) code.append(m.group(1));

        mvc.perform(post("/api/v1/auth/register")
                        .session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"csrf_cookie_user\",\"displayName\":\"CSRF Cookie\","
                                + "\"password\":\"Test1234\",\"captchaId\":\"" + captchaId
                                + "\",\"captchaCode\":\"" + code + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void missingCsrfHeaderIsRejected() throws Exception {
        Cookie xsrf = bootstrapXsrfCookie();
        mvc.perform(post("/api/v1/auth/register")
                        .cookie(xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"csrf_no_header\",\"displayName\":\"X\","
                                + "\"password\":\"Test1234\",\"captchaId\":\"x\",\"captchaCode\":\"y\"}"))
                .andExpect(status().isForbidden());
    }
}
