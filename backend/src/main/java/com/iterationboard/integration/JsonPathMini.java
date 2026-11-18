package com.iterationboard.integration;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * JSONPath 最小子集：
 * 支持 $.a.b、$.a[0].b、$[*]（数组遍历）、$ 根。够列表定位与字段映射用。
 */
public final class JsonPathMini {
    private JsonPathMini() {}

    /** 求值：返回命中的节点列表（[*] 可展开多值，其余至多一个）。 */
    public static List<JsonNode> eval(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return List.of();
        String p = path.strip();
        if (!p.startsWith("$")) return List.of();
        List<JsonNode> current = List.of(root);
        int i = 1;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '.') {
                int j = i + 1;
                while (j < p.length() && p.charAt(j) != '.' && p.charAt(j) != '[') j++;
                String key = p.substring(i + 1, j);
                List<JsonNode> next = new ArrayList<>();
                for (JsonNode n : current) {
                    if ("*".equals(key)) {
                        if (n.isArray()) n.forEach(next::add);
                    } else {
                        JsonNode v = n.path(key);
                        if (!v.isMissingNode()) next.add(v);
                    }
                }
                current = next;
                i = j;
            } else if (c == '[') {
                int j = p.indexOf(']', i);
                if (j < 0) return List.of();
                String inside = p.substring(i + 1, j).strip();
                List<JsonNode> next = new ArrayList<>();
                if ("*".equals(inside)) {
                    for (JsonNode n : current) if (n.isArray()) n.forEach(next::add);
                } else {
                    try {
                        int idx = Integer.parseInt(inside);
                        for (JsonNode n : current) {
                            if (n.isArray() && idx >= 0 && idx < n.size()) next.add(n.get(idx));
                        }
                    } catch (NumberFormatException e) {
                        return List.of();
                    }
                }
                current = next;
                i = j + 1;
            } else {
                return List.of();
            }
        }
        return current;
    }

    /** 取第一个命中节点的文本（无命中返回 null）。 */
    public static String text(JsonNode root, String path) {
        List<JsonNode> r = eval(root, path);
        if (r.isEmpty() || r.get(0).isNull()) return null;
        JsonNode n = r.get(0);
        return n.isValueNode() ? n.asText() : n.toString();
    }
}
