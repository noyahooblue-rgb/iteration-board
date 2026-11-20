package com.iterationboard.notify;

import com.iterationboard.domain.Notification;

import java.util.LinkedHashMap;
import java.util.Map;

/** 通知的类别归口与对外形状（契约 Notification / category）。 */
public final class NotificationView {
    private NotificationView() { }

    /** 事件类型 → 类别。ASSIGNMENT 与 NUDGE 不可关闭。 */
    public static String categoryOf(String type) {
        if (type == null) return "SYSTEM";
        return switch (type) {
            case "ASSIGN", "TRANSFER", "TASK_PENDING_ACCEPTANCE", "INVITE", "ACCEPTOR" -> "ASSIGNMENT";
            case "NUDGE", "NUDGE_RESPONSE" -> "NUDGE";
            case "MENTION" -> "MENTION";
            case "SUBMIT", "APPROVE", "REJECT", "BLOCK", "DECLINE", "RELEASE", "PROGRESS",
                 "CLAIM", "CANCEL", "COMMENT" -> "ITEM_UPDATE";
            case "JOIN", "JOIN_REQUEST", "JOIN_APPROVED", "JOIN_REJECTED", "ITERATION_STARTED", "ITERATION_FINISHED", "SYSTEM",
                 "FEEDBACK_STATUS", "FEEDBACK_COMMENT", "FEEDBACK_PLUS_ONE" -> "SYSTEM";
            default -> type.startsWith("REMINDER") ? "REMINDER" : "ITEM_UPDATE";
        };
    }

    public static boolean disableable(String category) {
        return !"ASSIGNMENT".equals(category) && !"NUDGE".equals(category);
    }

    public static Map<String, Object> toMap(Notification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("teamId", n.getTeam() == null ? null : n.getTeam().getId());
        m.put("type", n.getType());
        m.put("category", categoryOf(n.getType()));
        m.put("itemId", n.getItem() == null ? null : n.getItem().getId());
        m.put("itemCode", n.getItem() == null ? null : n.getItem().getCode());
        m.put("actorId", n.getActor() == null ? null : n.getActor().getId());
        m.put("content", n.getContent());
        m.put("readAt", n.getReadAt());
        m.put("createdAt", n.getCreatedAt());
        return m;
    }
}
