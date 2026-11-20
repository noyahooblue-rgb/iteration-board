package com.iterationboard.notify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.iterationboard.domain.AppUser;

import java.util.Set;

/**
 * 外观主题偏好（深色主题定稿 svw62u5r）：存于 app_user.preferences_json 根键 theme。
 * 取值 system / light / dark（暮光蓝灰）/ graphite（石墨）；未保存过默认 system（跟随系统）。
 * 旧版本从未在服务端存过主题（此前仅本地 localStorage 的 'dark'），'dark' 语义即暮光蓝灰，无迁移。
 */
public final class AppearancePrefs {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Set<String> MODES = Set.of("system", "light", "dark", "graphite");
    public static final String DEFAULT_MODE = "system";

    private AppearancePrefs() { }

    public static String of(AppUser user) {
        String json = user == null ? null : user.getPreferencesJson();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode theme = MAPPER.readTree(json).get("theme");
                if (theme != null && theme.isTextual() && MODES.contains(theme.asText())) {
                    return theme.asText();
                }
            } catch (Exception ignored) { }
        }
        return DEFAULT_MODE;
    }

    /** 合并写入主题偏好（非法值忽略，保持原值），返回新的 preferences_json。 */
    public static String merge(AppUser user, String theme) {
        try {
            ObjectNode root = (user.getPreferencesJson() == null || user.getPreferencesJson().isBlank())
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(user.getPreferencesJson());
            if (theme != null && MODES.contains(theme)) root.put("theme", theme);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("preferences_json 处理失败", e);
        }
    }
}
