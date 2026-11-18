package com.iterationboard.notify;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.iterationboard.domain.AppUser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知偏好：存于 app_user.preferences_json 的 notificationPrefs 键。
 * 类别全集 ASSIGNMENT / NUDGE / MENTION / ITEM_UPDATE / REMINDER / SYSTEM，默认全开；
 * ASSIGNMENT 与 NUDGE 强制开启。
 */
public final class NotificationPrefs {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final List<String> CATEGORIES =
            List.of("ASSIGNMENT", "NUDGE", "MENTION", "ITEM_UPDATE", "REMINDER", "SYSTEM");

    private NotificationPrefs() { }

    public static Map<String, Boolean> of(AppUser user) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        CATEGORIES.forEach(c -> out.put(c, true));
        String json = user == null ? null : user.getPreferencesJson();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode prefs = MAPPER.readTree(json).get("notificationPrefs");
                if (prefs != null && prefs.isObject()) {
                    prefs.properties().forEach(e -> {
                        if (out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue().asBoolean(true));
                    });
                }
            } catch (Exception ignored) { }
        }
        out.put("ASSIGNMENT", true);
        out.put("NUDGE", true);
        return out;
    }

    public static boolean enabled(AppUser user, String category) {
        return of(user).getOrDefault(category, true);
    }

    /** 合并写入偏好（不可关闭的类别强制 true），返回新的 preferences_json。 */
    public static String merge(AppUser user, Map<String, Boolean> patch) {
        try {
            ObjectNode root = (user.getPreferencesJson() == null || user.getPreferencesJson().isBlank())
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(user.getPreferencesJson());
            ObjectNode prefs = root.has("notificationPrefs") && root.get("notificationPrefs").isObject()
                    ? (ObjectNode) root.get("notificationPrefs")
                    : root.putObject("notificationPrefs");
            if (patch != null) {
                patch.forEach((k, v) -> {
                    if (CATEGORIES.contains(k) && v != null) prefs.put(k, v);
                });
            }
            prefs.put("ASSIGNMENT", true);
            prefs.put("NUDGE", true);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("preferences_json 处理失败", e);
        }
    }
}
