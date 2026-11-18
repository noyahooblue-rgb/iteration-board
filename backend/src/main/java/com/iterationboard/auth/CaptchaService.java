package com.iterationboard.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 本地图形验证码：服务端生成 4 位字符，存入会话，渲染为 SVG（纯文本+干扰线，无外部画图库）。
 * 验证码一次性使用，校验后即失效。
 */
@Service
public class CaptchaService {
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String SESSION_KEY = "captchaCodes";
    private final SecureRandom random = new SecureRandom();

    public record Issued(String captchaId, String imageSvg) {}

    @SuppressWarnings("unchecked")
    private Map<String, String> store(HttpSession session) {
        Object v = session.getAttribute(SESSION_KEY);
        if (v instanceof Map) return (Map<String, String>) v;
        Map<String, String> m = new HashMap<>();
        session.setAttribute(SESSION_KEY, m);
        return m;
    }

    public Issued issue(HttpSession session) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 4; i++) code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        String id = UUID.randomUUID().toString();
        store(session).put(id, code.toString());
        return new Issued(id, renderSvg(code.toString()));
    }

    /** 校验并消费验证码。 */
    public boolean verify(HttpSession session, String captchaId, String captchaCode) {
        if (session == null || captchaId == null || captchaCode == null) return false;
        String expected = store(session).remove(captchaId);
        return expected != null && expected.equalsIgnoreCase(captchaCode.trim());
    }

    public boolean present(HttpSession session, String captchaId) {
        return session != null && captchaId != null && store(session).containsKey(captchaId);
    }

    private String renderSvg(String code) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"40\" viewBox=\"0 0 120 40\">");
        sb.append("<rect width=\"120\" height=\"40\" fill=\"#eef2ff\"/>");
        for (int i = 0; i < 5; i++) {
            int x1 = random.nextInt(120), y1 = random.nextInt(40), x2 = random.nextInt(120), y2 = random.nextInt(40);
            sb.append("<line x1=\"").append(x1).append("\" y1=\"").append(y1)
              .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2)
              .append("\" stroke=\"#a5b4fc\" stroke-width=\"1\"/>");
        }
        String[] colors = {"#1d4ed8", "#7c3aed", "#db2777", "#047857"};
        for (int i = 0; i < code.length(); i++) {
            int x = 18 + i * 26;
            int y = 27 + random.nextInt(5) - 2;
            int rot = random.nextInt(25) - 12;
            sb.append("<text x=\"").append(x).append("\" y=\"").append(y)
              .append("\" font-size=\"22\" font-family=\"monospace\" font-weight=\"bold\" fill=\"")
              .append(colors[i % colors.length])
              .append("\" transform=\"rotate(").append(rot).append(' ').append(x).append(' ').append(y).append(")\">")
              .append(code.charAt(i)).append("</text>");
        }
        sb.append("</svg>");
        return sb.toString();
    }
}
