package com.iterationboard.ai;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.PlatformTime;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.item.ItemService;
import com.iterationboard.item.ItemTransitionService;
import com.iterationboard.reminder.RiskService;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 六项 AI 功能编排：模型只出建议，写入一律走既有 item/report 服务与状态机。
 */
@Service
public class AiFeatureService {
    private final AiGateway gateway;
    private final TeamService teamService;
    private final TeamMemberRepo memberRepo;
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final ItemService itemService;
    private final RiskService riskService;
    private final AiQaRecordRepo qaRecordRepo;
    private final ObjectMapper om;
    private final PlatformTime platformTime;

    public AiFeatureService(AiGateway gateway, TeamService teamService, TeamMemberRepo memberRepo,
                            ItemRepo itemRepo, ItemOwnerRepo ownerRepo, ItemService itemService,
                            RiskService riskService, AiQaRecordRepo qaRecordRepo, ObjectMapper om, PlatformTime platformTime) {
        this.gateway = gateway;
        this.teamService = teamService;
        this.memberRepo = memberRepo;
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.itemService = itemService;
        this.riskService = riskService;
        this.qaRecordRepo = qaRecordRepo;
        this.om = om;
        this.platformTime = platformTime;
    }

    private String json(ObjectNode n) {
        try { return om.writeValueAsString(n); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void requirePo(Long teamId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        if (me.getRole() != Role.PO) throw ApiException.forbidden("仅 PO 可使用该 AI 功能");
    }

    private Map<String, Object> memberNameMap(Long teamId) {
        Map<String, Object> byName = new LinkedHashMap<>();
        for (TeamMember m : memberRepo.findByTeamId(teamId)) byName.put(m.getUser().getDisplayName(), m.getUser().getId());
        return byName;
    }

    // ---------- 7.2 粘贴导入：解析 + 程序匹配，返回预览行 ----------
    @Transactional // D-1：经 AiGateway 写 ai_call_log，必须可写事务（只读事务会让日志 INSERT 失败并毒化外层事务）
    public Map<String, Object> pasteImport(Long teamId, AppUser user, String text, String imageDataUrl) {
        requirePo(teamId, user);
        ObjectNode payload = om.createObjectNode();
        payload.put("text", "<<<DATA 开始>>>\n" + (text == null ? "" : text) + "\n<<<DATA 结束>>>");
        JsonNode result = gateway.chatJson(teamId, user, "paste-import", json(payload), imageDataUrl);
        Map<String, Object> nameToId = memberNameMap(teamId);
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode arr = result.path("rows");
        int total = arr.isArray() ? arr.size() : 0;
        if (arr.isArray()) {
            for (JsonNode rn : arr) {
                if (rows.size() >= 100) break;
                Map<String, Object> row = new LinkedHashMap<>();
                String externalId = rn.path("externalId").isNull() ? null : rn.path("externalId").asText(null);
                String title = rn.path("title").asText("");
                String description = rn.path("description").isNull() ? null : rn.path("description").asText(null);
                String due = rn.path("dueDate").isNull() ? null : rn.path("dueDate").asText(null);
                row.put("externalId", externalId);
                row.put("title", title);
                row.put("type", "TASK");
                row.put("description", description);
                row.put("priority", rn.path("priority").asText("P2"));
                String ownerName = rn.path("ownerName").isNull() ? null : rn.path("ownerName").asText(null);
                row.put("ownerName", ownerName);
                row.put("ownerId", ownerName == null ? null : nameToId.get(ownerName));
                row.put("estimate", rn.path("estimate").isNull() ? null : rn.path("estimate").decimalValue());
                row.put("dueDate", due);
                row.put("parentExternalId", rn.path("parentExternalId").isNull() ? null : rn.path("parentExternalId").asText(null));
                row.put("sourceSnippet", rn.path("sourceSnippet").asText(""));
                row.put("confidence", rn.path("confidence").asDouble(0.5));
                // 程序匹配：外部编号与已有事项比对（系统名 AI_PASTE，与导入确认默认口径一致）
                Item existing = externalId == null ? null
                        : itemRepo.findFirstByTeamIdAndExternalSystemAndExternalId(teamId, "AI_PASTE", externalId).orElse(null);
                if (existing == null || existing.getDeletedAt() != null) {
                    row.put("matchStatus", "NEW");
                    row.put("existingItemId", null);
                    row.put("diff", Map.of());
                } else {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    if (!title.equals(existing.getTitle())) diff.put("title", Map.of("from", existing.getTitle(), "to", title));
                    if (description != null && !description.equals(existing.getDescription()))
                        diff.put("description", Map.of("from", existing.getDescription() == null ? "" : existing.getDescription(), "to", description));
                    String existingDue = existing.getDueDate() == null ? null : existing.getDueDate().toString();
                    if (due != null && !due.equals(existingDue))
                        diff.put("dueDate", Map.of("from", existingDue == null ? "" : existingDue, "to", due));
                    row.put("matchStatus", diff.isEmpty() ? "UNCHANGED" : "UPDATE");
                    row.put("existingItemId", existing.getId());
                    row.put("diff", diff);
                }
                rows.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("truncated", total > 100);
        return out;
    }

    // ---------- 7.3 一句话更新：解析为动作卡（不写库） ----------
    @Transactional // D-1：经 AiGateway 写 ai_call_log，必须可写事务（只读事务会让日志 INSERT 失败并毒化外层事务）
    public Map<String, Object> quickUpdate(Long teamId, AppUser user, String sentence) {
        teamService.requireMember(teamId, user);
        if (sentence == null || sentence.isBlank()) throw ApiException.validation("句子不能为空");
        List<Item> mine = myUnfinishedItems(teamId, user);
        ObjectNode payload = om.createObjectNode();
        payload.put("sentence", sentence);
        ArrayNode itemsArr = payload.putArray("items");
        for (Item i : mine) {
            ObjectNode n = itemsArr.addObject();
            n.put("id", i.getId());
            n.put("code", i.getCode());
            n.put("title", i.getTitle());
            n.put("status", i.getStatus().name());
            n.put("progressPercent", i.getProgressPercent());
        }
        ArrayNode membersArr = payload.putArray("members");
        for (TeamMember m : memberRepo.findByTeamId(teamId)) {
            ObjectNode n = membersArr.addObject();
            n.put("id", m.getUser().getId());
            n.put("name", m.getUser().getDisplayName());
        }
        JsonNode result = gateway.chatJson(teamId, user, "quick-update", json(payload), null);
        Map<String, Object> nameToId = memberNameMap(teamId);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (JsonNode an : result.path("actions")) {
            Map<String, Object> a = new LinkedHashMap<>();
            String kind = an.path("kind").asText("");
            a.put("kind", kind);
            if ("UPDATE_ITEM".equals(kind)) {
                long itemId = an.path("itemId").asLong(0);
                Item item = mine.stream().filter(i -> i.getId() == itemId).findFirst().orElse(null);
                if (item == null) continue; // 模型给出的事项不在「我负责的未完成」范围内，丢弃不猜
                a.put("itemId", item.getId());
                a.put("itemCode", item.getCode());
                a.put("text", an.path("text").asText(""));
                a.put("percent", an.path("percent").isNull() ? null : an.path("percent").asInt());
                a.put("submitReview", an.path("submitReview").asBoolean(false));
                a.put("blocked", an.path("blocked").asBoolean(false));
                a.put("blockReason", an.path("blockReason").isNull() ? null : an.path("blockReason").asText());
                a.put("needsBlockConfirm", an.path("needsBlockConfirm").asBoolean(false));
                List<Long> cands = new ArrayList<>();
                for (JsonNode c : an.path("candidateItemIds")) {
                    long cid = c.asLong();
                    if (mine.stream().anyMatch(i -> i.getId() == cid)) cands.add(cid);
                }
                a.put("candidateItemIds", cands.stream().limit(3).toList());
            } else if ("CREATE_ITEM".equals(kind)) {
                a.put("type", an.path("type").asText("ADHOC"));
                a.put("title", an.path("title").asText(""));
                String ownerName = an.path("ownerName").isNull() ? null : an.path("ownerName").asText(null);
                a.put("ownerName", ownerName);
                a.put("ownerId", ownerName == null ? null : nameToId.get(ownerName));
                a.put("dueDate", an.path("dueDate").isNull() ? null : an.path("dueDate").asText(null));
            } else {
                continue;
            }
            actions.add(a);
        }
        List<String> unmatched = new ArrayList<>();
        for (JsonNode un : result.path("unmatched")) unmatched.add(un.asText());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actions", actions);
        out.put("unmatched", unmatched);
        return out;
    }

    private List<Item> myUnfinishedItems(Long teamId, AppUser user) {
        java.util.Set<Long> ids = new java.util.HashSet<>(
                ownerRepo.findByUserId(user.getId()).stream().map(o -> o.getItem().getId()).toList());
        return itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null && i.getType() != ItemType.TASK && ids.contains(i.getId()))
                .filter(i -> i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED)
                .toList();
    }

    public record QuickAction(String kind, Long itemId, String text, Integer percent, Boolean submitReview,
                              Boolean blocked, String blockReason, String type, String title, Long ownerId, LocalDate dueDate) {}

    /** 一句话更新确认执行：动作经既有进展/流转/创建服务落库（幂等键在控制器层）。 */
    @Transactional
    public List<Map<String, Object>> quickUpdateApply(Long teamId, AppUser user, List<QuickAction> actions) {
        teamService.requireMember(teamId, user);
        if (actions == null || actions.isEmpty()) throw ApiException.validation("没有要执行的动作");
        List<Map<String, Object>> results = new ArrayList<>();
        for (QuickAction a : actions) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", a.kind());
            if ("UPDATE_ITEM".equals(a.kind())) {
                if (a.itemId() == null) throw ApiException.validation("UPDATE_ITEM 缺少 itemId");
                String text = a.text() == null || a.text().isBlank() ? "进展更新" : a.text();
                itemService.addProgress(teamId, a.itemId(), user, text, a.percent(),
                        a.blocked(), a.blockReason(), null, "NORMAL");
                if (Boolean.TRUE.equals(a.submitReview())) {
                    Item fresh = itemService.get(teamId, a.itemId(), user);
                    if (fresh.getStatus() == ItemStatus.IN_PROGRESS) {
                        itemService.transition(teamId, a.itemId(), user, ItemTransitionService.Action.SUBMIT,
                                fresh.getVersion(), null, null);
                    }
                }
                r.put("itemId", a.itemId());
                r.put("ok", true);
            } else if ("CREATE_ITEM".equals(a.kind())) {
                ItemType type = parseType(a.type());
                boolean assign = a.ownerId() != null;
                Item created = itemService.create(teamId, user, new ItemService.CreateItemCmd(
                        type, a.title(), null, null, assign ? "ASSIGN" : "POOL",
                        assign ? List.of(a.ownerId()) : null, null, null, a.dueDate(), null,
                        null, null, null, type == ItemType.STORY ? StoryCategory.OTHER : null, null));
                r.put("itemId", created.getId());
                r.put("ok", true);
            } else {
                throw ApiException.validation("未知动作类型 " + a.kind());
            }
            results.add(r);
        }
        return results;
    }

    private ItemType parseType(String t) {
        if (t == null) return ItemType.ADHOC;
        try {
            ItemType v = ItemType.valueOf(t);
            return v == ItemType.TASK ? ItemType.ADHOC : v;
        } catch (Exception e) { return ItemType.ADHOC; }
    }

    // ---------- 7.5 AI 拆故事（修订四 P06：组长和 PO 都可；绝不输出建议负责人） ----------
    @Transactional // D-1：经 AiGateway 写 ai_call_log，必须可写事务（只读事务会让日志 INSERT 失败并毒化外层事务）
    public Map<String, Object> splitStories(Long teamId, AppUser user, Long taskId) {
        TeamMember me = teamService.requireMember(teamId, user);
        if (me.getRole() != Role.PO && me.getRole() != Role.LEADER) {
            throw ApiException.forbidden("只有组长或 PO 可以使用 AI 拆故事");
        }
        Item task = itemService.get(teamId, taskId, user);
        if (task.getType() != ItemType.TASK) throw ApiException.validation("AI 拆故事只针对任务");
        ObjectNode payload = om.createObjectNode();
        payload.put("title", task.getTitle());
        payload.put("description", task.getDescription() == null ? "" : task.getDescription());
        ArrayNode existing = payload.putArray("existingStories");
        itemService.childrenOf(task).forEach(s -> existing.add(s.getTitle()));
        JsonNode result = gateway.chatJson(teamId, user, "split-stories", json(payload), null);
        List<Map<String, Object>> stories = new ArrayList<>();
        for (JsonNode sn : result.path("stories")) {
            Map<String, Object> s = new LinkedHashMap<>();
            String cat = sn.path("storyCategory").asText("OTHER");
            try { StoryCategory.valueOf(cat); } catch (Exception e) { cat = "OTHER"; }
            s.put("storyCategory", cat);
            s.put("title", sn.path("title").asText(""));
            s.put("description", sn.path("description").asText(""));
            s.put("estimate", sn.path("estimate").isNull() ? null : sn.path("estimate").decimalValue());
            stories.add(s); // 只透出白名单字段：即使模型返回建议负责人也不透出
        }
        List<String> questions = new ArrayList<>();
        for (JsonNode q : result.path("clarifyingQuestions")) questions.add(q.asText());
        List<String> points = new ArrayList<>();
        for (JsonNode p : result.path("acceptancePoints")) points.add(p.asText());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("stories", stories);
        out.put("clarifyingQuestions", questions);
        out.put("acceptancePoints", points);
        return out;
    }

    // ---------- 7.6 风险摘要：规则算信号，AI 只汇总排序；不可用时返回规则清单原样 ----------
    @Transactional // D-1：经 AiGateway 写 ai_call_log，必须可写事务（只读事务会让日志 INSERT 失败并毒化外层事务）
    public Map<String, Object> riskSummary(Long teamId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        if (me.getRole() != Role.LEADER && me.getRole() != Role.PO) {
            throw ApiException.forbidden("风险清单仅组长、PO 可见");
        }
        List<RiskService.RiskSignal> signals = riskService.computeRisks(me.getTeam(), platformTime.today());
        List<Map<String, Object>> signalMaps = signals.stream().map(RiskService.RiskSignal::toMap).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signals", signalMaps);
        List<Map<String, Object>> top = List.of();
        boolean ai = false;
        if (gateway.featureAvailable(teamId, "risk-summary") && !signals.isEmpty()) {
            try {
                ObjectNode payload = om.createObjectNode();
                ArrayNode arr = payload.putArray("signals");
                for (RiskService.RiskSignal s : signals) {
                    ObjectNode n = arr.addObject();
                    n.put("itemId", s.itemId());
                    n.put("itemCode", s.itemCode());
                    n.put("rule", s.rule());
                    n.put("severity", s.severity());
                    n.put("message", s.message());
                }
                JsonNode result = gateway.chatJson(teamId, user, "risk-summary", json(payload), null);
                List<Map<String, Object>> tops = new ArrayList<>();
                java.util.Set<String> validCodes = new java.util.HashSet<>();
                signals.forEach(s -> { if (s.itemCode() != null) validCodes.add(s.itemCode()); });
                for (JsonNode tn : result.path("top")) {
                    String code = tn.path("itemCode").isNull() ? null : tn.path("itemCode").asText();
                    if (code != null && !validCodes.contains(code)) continue; // 编造的编号丢弃
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("itemId", tn.path("itemId").isNull() ? null : tn.path("itemId").asLong());
                    t.put("itemCode", code);
                    t.put("reason", tn.path("reason").asText(""));
                    t.put("action", tn.path("action").asText(""));
                    tops.add(t);
                }
                top = tops.stream().limit(3).toList();
                ai = true;
            } catch (Exception e) {
                top = List.of();
                ai = false;
            }
        }
        out.put("top", top);
        out.put("aiGenerated", ai);
        return out;
    }

    // ---------- 7.7 问答：模型转条件 → 程序参数化取数（强制 team 过滤） → 模型据数据作答 ----------
    @Transactional
    public Map<String, Object> qa(Long teamId, AppUser user, String question) {
        teamService.requireMember(teamId, user);
        if (question == null || question.isBlank()) throw ApiException.validation("问题不能为空");
        // 阶段 1：转结构化条件
        ObjectNode qp = om.createObjectNode();
        qp.put("stage", "query");
        qp.put("question", question);
        ArrayNode membersArr = qp.putArray("members");
        Map<String, Long> nameToId = new LinkedHashMap<>();
        for (TeamMember m : memberRepo.findByTeamId(teamId)) {
            ObjectNode n = membersArr.addObject();
            n.put("id", m.getUser().getId());
            n.put("name", m.getUser().getDisplayName());
            nameToId.put(m.getUser().getDisplayName(), m.getUser().getId());
        }
        JsonNode cond = gateway.chatJson(teamId, user, "qa", json(qp), null);
        // 阶段 2：程序取数（只查本组，条件全部来自结构化字段）
        String code = cond.path("code").isNull() ? null : cond.path("code").asText(null);
        String ownerName = cond.path("ownerName").isNull() ? null : cond.path("ownerName").asText(null);
        String status = cond.path("status").isNull() ? null : cond.path("status").asText(null);
        boolean blockedOnly = cond.path("blocked").asBoolean(false);
        String keyword = cond.path("keyword").isNull() ? null : cond.path("keyword").asText(null);
        Long ownerId = ownerName == null ? null : nameToId.get(ownerName);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.strip();
        // D-4：必须按结构化条件真过滤。旧实现把「关键词==问题原文」当作放行条件，
        // mock 模式下关键词恒等于问题原文，于是任何问题都全量命中、found 恒真。
        // 现在：关键词真实匹配标题/描述；且一个结构化条件都没有时不取数（不拿全量罗列冒充答案）。
        boolean hasCondition = code != null || ownerId != null || status != null || blockedOnly || kw != null;
        List<Item> matched = !hasCondition ? List.of() : itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null)
                .filter(i -> code == null || i.getCode().equalsIgnoreCase(code))
                .filter(i -> status == null || i.getStatus().name().equals(status))
                .filter(i -> !blockedOnly || i.isBlocked())
                .filter(i -> ownerId == null || ownerRepo.findByItemId(i.getId()).stream()
                        .anyMatch(o -> o.getUser().getId().equals(ownerId)))
                .filter(i -> kw == null || i.getTitle().contains(kw)
                        || (i.getDescription() != null && i.getDescription().contains(kw)))
                .limit(20)
                .toList();
        // 阶段 3：据数据作答
        ObjectNode ap = om.createObjectNode();
        ap.put("stage", "answer");
        ap.put("question", question);
        ArrayNode data = ap.putArray("data");
        List<String> codes = new ArrayList<>();
        for (Item i : matched) {
            ObjectNode n = data.addObject();
            n.put("code", i.getCode());
            n.put("title", i.getTitle());
            n.put("status", i.getStatus().name());
            n.put("progressPercent", i.getProgressPercent());
            ArrayNode owners = n.putArray("ownerNames");
            ownerRepo.findByItemId(i.getId()).forEach(o -> owners.add(o.getUser().getDisplayName()));
            n.put("dueDate", i.getDueDate() == null ? null : i.getDueDate().toString());
            n.put("blocked", i.isBlocked());
            codes.add(i.getCode());
        }
        JsonNode ans = gateway.chatJson(teamId, user, "qa", json(ap), null);
        boolean found = ans.path("found").asBoolean(false) && !matched.isEmpty();
        String answer = found ? ans.path("answer").asText("") : "在本小组内没有查到符合条件的事项。";
        List<String> refs = new ArrayList<>();
        for (JsonNode rn : ans.path("refs")) {
            String c = rn.asText();
            if (codes.contains(c) && !refs.contains(c)) refs.add(c);
        }
        if (!found) refs = List.of();
        AiQaRecord record = qaRecordRepo.save(new AiQaRecord(
                teamService.requireTeam(teamId), user, question, answer, found, writeRefs(refs)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordId", record.getId());
        out.put("found", found);
        out.put("answer", answer);
        out.put("refs", refs);
        return out;
    }

    private String writeRefs(List<String> refs) {
        try { return om.writeValueAsString(refs); } catch (Exception e) { return "[]"; }
    }

    @Transactional(readOnly = true)
    public List<AiQaRecord> qaHistory(Long teamId, AppUser user) {
        teamService.requireMember(teamId, user);
        return qaRecordRepo.findByTeamIdAndUserIdOrderByCreatedAtDesc(teamId, user.getId());
    }

    @Transactional
    public void deleteQaRecord(Long teamId, Long recordId, AppUser user) {
        teamService.requireMember(teamId, user);
        AiQaRecord r = qaRecordRepo.findById(recordId)
                .filter(x -> x.getTeam().getId().equals(teamId) && x.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("问答记录不存在"));
        qaRecordRepo.delete(r);
    }

    /** BigDecimal 透出辅助（预览行 estimate）。 */
    static BigDecimal dec(JsonNode n) { return n == null || n.isNull() ? null : n.decimalValue(); }

    public static ApiException aiHttp(int status, String code, String msg) {
        return new ApiException(HttpStatus.valueOf(status), code, msg);
    }
}
