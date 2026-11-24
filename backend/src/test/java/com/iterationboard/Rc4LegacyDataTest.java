package com.iterationboard;

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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * rc4 旧版本数据样本集成测试（D1/D2/D3 + B）：
 * 模拟从旧版本升级上来的库里存在的「现行代码预期之外」的数据形态——
 * - item.status / item.type 为现行枚举外的值（旧枚举 OPEN / REQUIREMENT）；
 * - feedback_image.content_base64 带 data: 前缀、或按 MIME 习惯每 76 字符 CRLF 折行、或真损坏。
 * 预期：读取侧一律容错兜底（原样返回 / 规范化解码 / 业务错误），不再 500。
 * MockMvc + 真实 PostgreSQL，事务回滚隔离；全部测试数据为虚构账号与事项。
 * 登录/会话 helper 口径同 Rc3IntegrationTest（captcha + login，GOOD_PW）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Rc4LegacyDataTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    private static final String GOOD_PW = "passw0rd1!";
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};
    /** 足够长以触发 MIME 每 76 字符折行（魔数仍为 PNG 头，其余为填充）。 */
    private static final byte[] PNG_BYTES_LONG = buildLongPng();

    private static byte[] buildLongPng() {
        byte[] b = new byte[100];
        System.arraycopy(PNG_BYTES, 0, b, 0, 8);
        for (int i = 8; i < b.length; i++) b[i] = (byte) (i * 7 % 251);
        return b;
    }

    // ---------- helpers（与 Rc3IntegrationTest 同口径） ----------

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

    private MockHttpSession loginOk(String loginName, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        MvcResult r = mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + loginName + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("登录应成功: %s", loginName).isEqualTo(200);
        return session;
    }

    /** 直接造数（绕过注册校验）：平台管理员场景用。 */
    private void insertUser(String loginName, String rawPassword, boolean platformAdmin) {
        jdbc.update("insert into app_user (login_name, display_name, password_hash, avatar_color, platform_admin, status,"
                        + " must_change_password, failed_attempts, password_version, created_at, updated_at)"
                        + " values (?, ?, ?, 'indigo', ?, 'ACTIVE', false, 0, 0, now(), now())",
                loginName, "虚构-" + loginName, passwordEncoder.encode(rawPassword), platformAdmin);
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

    private long createItem(MockHttpSession s, long teamId, String json) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/teams/" + teamId + "/items").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long createFeedback(MockHttpSession s, String title) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/feedback").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ISSUE\",\"title\":\"" + title + "\",\"description\":\"虚构描述\"}"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** 直接插入旧形态图片行（绕过 API 的严格上传校验，模拟旧版本/导入落库的数据）。 */
    private long insertLegacyImage(long feedbackId, String fileName, String contentType, String contentBase64, long sizeBytes) {
        jdbc.update("insert into feedback_image (feedback_id, file_name, content_type, size_bytes, content_base64, created_at)"
                        + " values (?, ?, ?, ?, ?, now())",
                feedbackId, fileName, contentType, sizeBytes, contentBase64);
        Long id = jdbc.queryForObject("select id from feedback_image where feedback_id = ? and file_name = ?",
                Long.class, feedbackId, fileName);
        return id;
    }

    // ---------- D1 + D3-1：resolution 对枚举外旧值的容错 ----------

    @Test
    void resolution_legacyEnumValues_returnedRawNot500() throws Exception {
        MockHttpSession leader = register("rc4lead", "虚构组长");
        TeamCtx team = createTeam(leader, "虚构旧数据组", "RCQA");
        long itemId = createItem(leader, team.teamId(), "{\"type\":\"TASK\",\"title\":\"虚构旧数据事项\"}");

        // 旧库形态：status 为现行枚举外的值（旧枚举 OPEN）
        jdbc.update("update item set status = 'OPEN' where id = ?", itemId);
        MvcResult r1 = mvc.perform(get("/api/v1/items/" + itemId + "/resolution").session(leader))
                .andExpect(status().isOk()).andReturn();
        String b1 = r1.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(b1, "$.status")).isEqualTo("OPEN");
        assertThat((String) JsonPath.read(b1, "$.type")).isEqualTo("TASK");
        assertThat((Boolean) JsonPath.read(b1, "$.hasAccess")).isTrue();

        // 旧库形态：type 为现行枚举外的值（旧枚举 REQUIREMENT）
        jdbc.update("update item set type = 'REQUIREMENT' where id = ?", itemId);
        MvcResult r2 = mvc.perform(get("/api/v1/items/" + itemId + "/resolution").session(leader))
                .andExpect(status().isOk()).andReturn();
        String b2 = r2.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(b2, "$.type")).isEqualTo("REQUIREMENT");
        assertThat((String) JsonPath.read(b2, "$.status")).isEqualTo("OPEN");
    }

    // ---------- B + D3-3：resolution 不存在 → 专属错误码 ----------

    @Test
    void resolution_missingItem_returnsItemNotFoundCode() throws Exception {
        MockHttpSession user = register("rc4solo", "虚构查询者");
        mvc.perform(get("/api/v1/items/99999999/resolution").session(user))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
    }

    // ---------- D2 + D3-2：反馈图片旧形态（data: 前缀 / CRLF 折行） ----------

    @Test
    void feedbackImages_legacyBase64Forms_servedByteIdentical() throws Exception {
        insertUser("rc4fadmin", "Admin#1234", true);
        MockHttpSession admin = loginOk("rc4fadmin", "Admin#1234");
        MockHttpSession author = register("rc4fauthor", "虚构反馈人");
        long fid = createFeedback(author, "虚构旧图片反馈");

        // (a) content_base64 带 "data:image/png;base64," 前缀（旧前端/导入形态）
        String prefixed = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);
        long imgA = insertLegacyImage(fid, "legacy-a.png", "image/png", prefixed, PNG_BYTES.length);
        MvcResult ra = mvc.perform(get("/api/v1/feedback/" + fid + "/images/" + imgA).session(author))
                .andExpect(status().isOk()).andReturn();
        assertThat(ra.getResponse().getContentType()).contains("image/png");
        assertThat(ra.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);

        // (b) 标准 Base64 按 MIME 习惯每 76 字符 CRLF 折行
        String folded = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(PNG_BYTES_LONG);
        assertThat(folded).contains("\r\n"); // 前提：样本确实带折行，否则本用例测了个寂寞
        long imgB = insertLegacyImage(fid, "legacy-b.png", "image/png", folded, PNG_BYTES_LONG.length);
        MvcResult rb = mvc.perform(get("/api/v1/feedback/" + fid + "/images/" + imgB).session(admin))
                .andExpect(status().isOk()).andReturn();
        assertThat(rb.getResponse().getContentType()).contains("image/png");
        assertThat(rb.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES_LONG);
    }

    // ---------- D2 + D3-4：真损坏 Base64 → 业务错误而非 500 ----------

    @Test
    void feedbackImage_corruptBase64_returnsBusinessErrorNot500() throws Exception {
        MockHttpSession author = register("rc4fcorrupt", "虚构反馈人乙");
        long fid = createFeedback(author, "虚构损坏图片反馈");
        long imgId = insertLegacyImage(fid, "broken.png", "image/png", "!!!not-base64!!!", 0);
        mvc.perform(get("/api/v1/feedback/" + fid + "/images/" + imgId).session(author))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("IMAGE_DATA_INVALID"));
    }
}
