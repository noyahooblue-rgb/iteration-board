package com.iterationboard.team;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.iterationboard.domain.Team;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 小组设置（team.settings_json）的读写口径（契约 0.3.0 TeamSettings）。
 * 字段：capacityPointsPerWorkday（容量系数，默认 1）、reminderThresholds、specialHolidays / specialWorkdays（特殊非工作日/特殊工作日）。
 */
public final class TeamSettings {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final BigDecimal capacityPointsPerWorkday;
    public final Thresholds thresholds;
    /** P17：故事/任务默认验收角色（settings_json 的 storyAcceptorRole / taskAcceptorRole，默认 PO 验故事、组长验任务）。 */
    public final String storyAcceptorRole;
    public final String taskAcceptorRole;
    public final Set<LocalDate> specialHolidays;
    public final Set<LocalDate> specialWorkdays;

    public record Thresholds(int staleInProgressWorkdays, int notStartedWorkdays, int reviewPendingWorkdays,
                             int unclaimedWorkdays, int blockedWorkdays, int dueSoonWorkdays) {
        public static Thresholds defaults() { return new Thresholds(2, 2, 2, 3, 2, 1); }
    }

    private TeamSettings(BigDecimal cap, Thresholds t, Set<LocalDate> hol, Set<LocalDate> work) {
        this(cap, t, hol, work, "PO", "LEADER");
    }

    private TeamSettings(BigDecimal cap, Thresholds t, Set<LocalDate> hol, Set<LocalDate> work,
                         String storyAcceptorRole, String taskAcceptorRole) {
        this.capacityPointsPerWorkday = cap;
        this.thresholds = t;
        this.specialHolidays = hol;
        this.specialWorkdays = work;
        this.storyAcceptorRole = storyAcceptorRole;
        this.taskAcceptorRole = taskAcceptorRole;
    }

    private static String validRole(String v, String fallback) {
        return ("LEADER".equals(v) || "PO".equals(v)) ? v : fallback;
    }

    public static TeamSettings from(Team team) {
        BigDecimal cap = BigDecimal.ONE;
        Thresholds t = Thresholds.defaults();
        String storyRole = "PO";
        String taskRole = "LEADER";
        Set<LocalDate> hol = new LinkedHashSet<>();
        Set<LocalDate> work = new LinkedHashSet<>();
        String json = team == null ? null : team.getSettingsJson();
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(json);
                if (root.hasNonNull("capacityPointsPerWorkday")) {
                    cap = root.get("capacityPointsPerWorkday").decimalValue();
                }
                JsonNode th = root.get("reminderThresholds");
                if (th != null && th.isObject()) {
                    t = new Thresholds(
                            th.path("staleInProgressWorkdays").asInt(t.staleInProgressWorkdays()),
                            th.path("notStartedWorkdays").asInt(t.notStartedWorkdays()),
                            th.path("reviewPendingWorkdays").asInt(t.reviewPendingWorkdays()),
                            th.path("unclaimedWorkdays").asInt(t.unclaimedWorkdays()),
                            th.path("blockedWorkdays").asInt(t.blockedWorkdays()),
                            th.path("dueSoonWorkdays").asInt(t.dueSoonWorkdays()));
                }
                storyRole = validRole(root.path("storyAcceptorRole").asText(null), storyRole);
                taskRole = validRole(root.path("taskAcceptorRole").asText(null), taskRole);
                readDates(root.get("specialHolidays"), hol);
                readDates(root.get("specialWorkdays"), work);
            } catch (Exception ignored) {
                // 设置 JSON 损坏时回落默认值，不阻断业务
            }
        }
        return new TeamSettings(cap, t, hol, work, storyRole, taskRole);
    }

    private static void readDates(JsonNode node, Set<LocalDate> out) {
        if (node != null && node.isArray()) {
            for (JsonNode d : node) {
                try { out.add(LocalDate.parse(d.asText())); } catch (Exception ignored) { }
            }
        }
    }

    /** 序列化为契约 TeamSettings 形状（API 返回用）。 */
    public ObjectNode toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("capacityPointsPerWorkday", capacityPointsPerWorkday);
        ObjectNode th = root.putObject("reminderThresholds");
        th.put("staleInProgressWorkdays", thresholds.staleInProgressWorkdays());
        th.put("notStartedWorkdays", thresholds.notStartedWorkdays());
        th.put("reviewPendingWorkdays", thresholds.reviewPendingWorkdays());
        th.put("unclaimedWorkdays", thresholds.unclaimedWorkdays());
        th.put("blockedWorkdays", thresholds.blockedWorkdays());
        th.put("dueSoonWorkdays", thresholds.dueSoonWorkdays());
        root.put("storyAcceptorRole", storyAcceptorRole);
        root.put("taskAcceptorRole", taskAcceptorRole);
        ArrayNode hol = root.putArray("specialHolidays");
        specialHolidays.forEach(d -> hol.add(d.toString()));
        ArrayNode work = root.putArray("specialWorkdays");
        specialWorkdays.forEach(d -> work.add(d.toString()));
        return root;
    }

    /** 把请求中的 settings 节点合并进现有 settings_json（部分更新）。 */
    public static String mergeInto(String existingJson, JsonNode patch) {
        try {
            ObjectNode root = (existingJson == null || existingJson.isBlank())
                    ? MAPPER.createObjectNode()
                    : (ObjectNode) MAPPER.readTree(existingJson);
            if (patch != null && patch.isObject()) {
                patch.properties().forEach(e -> root.set(e.getKey(), e.getValue()));
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("settings_json 处理失败", e);
        }
    }
}
