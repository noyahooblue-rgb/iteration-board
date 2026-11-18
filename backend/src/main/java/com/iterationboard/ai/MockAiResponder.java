package com.iterationboard.ai;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模拟模式：不调外部服务，按入参确定性生成结构化样例结果，供开发与演示环境演示完整流程。
 * paste-import 按行拆解粘贴文本；其余功能按上下文（事项/成员/信号/事实表）拼装样例。
 */
@Component
public class MockAiResponder {
    private final ObjectMapper om;

    public MockAiResponder(ObjectMapper om) { this.om = om; }

    public LiveAiClient.Result respond(String feature, String system, String userPayload, String imageDataUrl) {
        JsonNode payload = parse(userPayload);
        String json = switch (feature) {
            case "paste-import" -> pasteImport(payload, imageDataUrl);
            case "quick-update" -> quickUpdate(payload);
            case "split-stories" -> splitStories(payload);
            case "summarize" -> summarize(payload);
            case "risk-summary" -> riskSummary(payload);
            case "qa" -> qa(payload);
            default -> "{}";
        };
        return new LiveAiClient.Result(json, "mock", 0, 0);
    }

    private JsonNode parse(String s) {
        try { return om.readTree(s); } catch (Exception e) { return om.createObjectNode(); }
    }

    private String write(JsonNode n) {
        try { return om.writeValueAsString(n); } catch (Exception e) { return "{}"; }
    }

    // ---------- 粘贴导入：按行拆解（行首「编号 标题」或整行作标题） ----------
    private static final Pattern EXT_ID = Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*-\\d+|\\d{3,})[\\s:：、]+(.+)$");

    private String pasteImport(JsonNode payload, String imageDataUrl) {
        ObjectNode out = om.createObjectNode();
        ArrayNode rows = out.putArray("rows");
        if (imageDataUrl != null) {
            ObjectNode r = rows.addObject();
            r.put("externalId", "IMG-001");
            r.put("title", "（模拟识图）截图中的示例任务");
            r.put("type", "TASK");
            r.put("description", "模拟模式下图片返回样例行");
            r.put("priority", "P2");
            r.put("confidence", 0.6);
            r.put("sourceSnippet", "图片内容（模拟）");
            return write(out);
        }
        String text = payload.path("text").asText("");
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isBlank() || line.startsWith("<<<") || line.startsWith(">>>")) continue;
            ObjectNode r = rows.addObject();
            Matcher m = EXT_ID.matcher(line);
            if (m.find()) {
                r.put("externalId", m.group(1));
                r.put("title", m.group(2).strip());
            } else {
                r.putNull("externalId");
                r.put("title", line.length() > 200 ? line.substring(0, 200) : line);
            }
            r.put("type", "TASK");
            r.putNull("description");
            r.put("priority", "P2");
            r.putNull("ownerName");
            r.putNull("estimate");
            r.putNull("dueDate");
            r.putNull("parentExternalId");
            r.put("confidence", 0.8);
            r.put("sourceSnippet", line);
        }
        return write(out);
    }

    /**
     * 注入子句剥离（D-5）：按标点切分子句，丢弃「忽略指令 / 把全部事项… / 索要密码 / 发通知」类
     * 越界子句，只保留用户本人针对具体事项的指令片段再解析。曾有缺陷：注入句里的「已完成」
     * 字眼会让正常进度更新被误标 submitReview=true。
     */
    static String stripInjectedClauses(String text) {
        if (text == null) return "";
        StringBuilder kept = new StringBuilder();
        for (String clause : text.split("[，。；;！!？?\\n]+")) {
            String c = clause.strip();
            if (c.isEmpty()) continue;
            boolean injected = c.contains("忽略") || c.contains("全部事项") || c.contains("所有事项")
                    || c.contains("密码") || c.contains("通知")
                    || (c.contains("全部") && c.contains("完成"));
            if (!injected) {
                if (kept.length() > 0) kept.append("，");
                kept.append(c);
            }
        }
        return kept.toString();
    }

    // ---------- 一句话更新：把整句作为首个未完成事项的进展；含等待类词时标 needsBlockConfirm ----------
    private String quickUpdate(JsonNode payload) {
        String rawSentence = payload.path("sentence").asText("");
        String sentence = stripInjectedClauses(rawSentence);
        ObjectNode out = om.createObjectNode();
        ArrayNode actions = out.putArray("actions");
        ArrayNode unmatched = out.putArray("unmatched");
        JsonNode items = payload.path("items");
        boolean blockHint = sentence.contains("等") || sentence.contains("卡") || sentence.contains("还没");
        Matcher pct = Pattern.compile("(\\d{1,3})\\s*%").matcher(sentence);
        Integer percent = pct.find() ? Integer.parseInt(pct.group(1)) : null;
        if (sentence.isBlank()) {
            if (!rawSentence.isBlank()) unmatched.add(rawSentence);
        } else if (items.isArray() && !items.isEmpty()) {
            ObjectNode a = actions.addObject();
            a.put("kind", "UPDATE_ITEM");
            a.put("itemId", items.get(0).path("id").asLong());
            a.put("itemCode", items.get(0).path("code").asText(""));
            a.put("text", sentence);
            if (percent != null) a.put("percent", percent);
            a.put("submitReview", sentence.contains("做完") || sentence.contains("完成") || (percent != null && percent == 100));
            a.put("blocked", false);
            a.put("needsBlockConfirm", blockHint);
            a.putArray("candidateItemIds");
        } else if (!sentence.isBlank()) {
            unmatched.add(sentence);
        }
        return write(out);
    }

    // ---------- AI 拆故事：两条样例故事 + 澄清问题 + 验收要点（绝不输出建议负责人） ----------
    private String splitStories(JsonNode payload) {
        String title = payload.path("title").asText("该任务");
        ObjectNode out = om.createObjectNode();
        ArrayNode stories = out.putArray("stories");
        ObjectNode s1 = stories.addObject();
        s1.put("storyCategory", "DEV");
        s1.put("title", title + "：核心功能实现");
        s1.put("description", "实现主体功能并自测通过（AI 估算，仅供参考）");
        s1.put("estimate", 5);
        ObjectNode s2 = stories.addObject();
        s2.put("storyCategory", "TEST");
        s2.put("title", title + "：联调与验收测试");
        s2.put("description", "覆盖主要场景的联调与回归测试（AI 估算，仅供参考）");
        s2.put("estimate", 3);
        ArrayNode q = out.putArray("clarifyingQuestions");
        q.add("该任务的验收标准由谁确认？");
        q.add("是否有外部系统依赖需要提前协调？");
        ArrayNode acc = out.putArray("acceptancePoints");
        acc.add("核心功能按描述完整交付");
        acc.add("异常场景有明确提示且不丢数据");
        return write(out);
    }

    // ---------- 自动汇总：模拟叙述直接基于事实表改写（编号/数字与事实表一致，可通过校验） ----------
    private String summarize(JsonNode payload) {
        String facts = payload.path("facts").asText("");
        ObjectNode out = om.createObjectNode();
        out.put("narrative", facts.isBlank() ? "本期暂无可汇总的数据。" : facts);
        return write(out);
    }

    // ---------- 风险摘要：取前 3 条规则信号 ----------
    private String riskSummary(JsonNode payload) {
        ObjectNode out = om.createObjectNode();
        ArrayNode top = out.putArray("top");
        JsonNode signals = payload.path("signals");
        if (signals.isArray()) {
            for (int i = 0; i < Math.min(3, signals.size()); i++) {
                JsonNode s = signals.get(i);
                ObjectNode t = top.addObject();
                t.put("itemId", s.path("itemId").isNull() ? null : s.path("itemId").asLong());
                t.put("itemCode", s.path("itemCode").asText(null));
                t.put("reason", s.path("message").asText(""));
                t.put("action", "请负责人今天内更新进展或说明阻塞原因");
            }
        }
        return write(out);
    }

    // ---------- 问答：query 阶段抽取编号/状态/负责人/关键词；answer 阶段据数据作答 ----------
    // D-4：旧实现把 keyword 恒设为问题原文，配合服务层放行条款导致任何问题都全量命中。
    // 现在与 mock-backend 口径对齐：先剥离注入子句，再抽结构化条件；关键词是去掉问句套话后
    // 的核心短语（泛指词如单独的「故事/事项」不算关键词），抽不到任何条件时不输出条件。
    private static final String[] QA_STATUS_PHRASES = {"验收中", "待认领", "进行中", "未开始", "已完成", "完成了", "做完了", "已取消"};
    private static final String[] QA_FILLERS = {"做到哪了", "到哪一步", "谁负责", "负责人是谁", "负责的", "负责",
            "请问", "麻烦", "帮我查", "帮我", "查一下", "看一下", "告诉我", "有没有", "有哪些", "哪个", "哪些",
            "那个", "这个", "已经", "本组", "小组", "关于", "的事项", "进展", "进度", "情况", "怎么样", "如何",
            "什么", "现在", "还有", "一下", "了吗", "列出", "给我", "所有", "全部", "吗", "呢", "了", "谁", "查"};

    private String qa(JsonNode payload) {
        String stage = payload.path("stage").asText("query");
        ObjectNode out = om.createObjectNode();
        if ("query".equals(stage)) {
            String question = stripInjectedClauses(payload.path("question").asText(""));
            String code = null;
            Matcher m = Pattern.compile("([A-Za-z]{2,5}-\\d+)").matcher(question);
            if (m.find()) { code = m.group(1).toUpperCase(); out.put("code", code); }
            String ownerName = null;
            for (JsonNode member : payload.path("members")) {
                String name = member.path("name").asText("");
                if (!name.isBlank() && question.contains(name)) { ownerName = name; out.put("ownerName", name); break; }
            }
            String status = null;
            if (question.contains("验收中")) status = "IN_REVIEW";
            else if (question.contains("待认领")) status = "UNCLAIMED";
            else if (question.contains("进行中")) status = "IN_PROGRESS";
            else if (question.contains("未开始")) status = "NOT_STARTED";
            else if (question.contains("已完成") || question.contains("完成了") || question.contains("做完了")) status = "DONE";
            else if (question.contains("已取消")) status = "CANCELLED";
            if (status != null) out.put("status", status);
            if (question.contains("阻塞")) out.put("blocked", true);
            // 关键词：编号已精确命中时不再叠加关键词（避免套话残片把编号命中误过滤掉）
            if (code == null) {
                String kw = question;
                if (ownerName != null) kw = kw.replace(ownerName, "");
                for (String p : QA_STATUS_PHRASES) kw = kw.replace(p, "");
                for (String f : QA_FILLERS) kw = kw.replace(f, "");
                kw = kw.replaceAll("[\\s，。；;！!？?、：:\"“”'‘’()]+", "").strip();
                if (kw.length() >= 2 && !java.util.Set.of("故事", "事项", "任务", "缺陷", "工作").contains(kw)) {
                    out.put("keyword", kw);
                }
            }
            return write(out);
        }
        JsonNode data = payload.path("data");
        if (!data.isArray() || data.isEmpty()) {
            out.put("found", false);
            out.put("answer", "在本小组内没有查到符合条件的事项。");
            out.putArray("refs");
            return write(out);
        }
        StringBuilder sb = new StringBuilder("共查到 ").append(data.size()).append(" 条：");
        ArrayNode refs = out.putArray("refs");
        for (JsonNode d : data) {
            String code = d.path("code").asText("");
            sb.append("\n- ").append(code).append(" ").append(d.path("title").asText(""))
              .append("（").append(d.path("status").asText("")).append("，进度 ")
              .append(d.path("progressPercent").asInt(0)).append("%）");
            if (!code.isBlank()) refs.add(code);
        }
        out.put("found", true);
        out.put("answer", sb.toString());
        return write(out);
    }
}
