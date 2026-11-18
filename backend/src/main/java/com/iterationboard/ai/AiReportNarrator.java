package com.iterationboard.ai;

import com.iterationboard.domain.AppUser;
import com.iterationboard.report.ReportNarrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动汇总的 AI 叙述实现：程序算事实表，模型只写叙述；
 * 生成后程序校验——文中事项编号必须真实存在于事实表、数字必须与事实表一致；
 * 不一致丢弃重跑一次，再不一致返回 empty，调用方用程序模板（aiGenerated=false）。
 * AI 不可用时同样返回 empty，报告生成不受影响。
 */
@Component
public class AiReportNarrator implements ReportNarrator {
    private static final Logger log = LoggerFactory.getLogger(AiReportNarrator.class);
    private static final Pattern CODE = Pattern.compile("[A-Za-z]{2,5}-\\d+");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?%?");

    private final AiGateway gateway;
    private final ObjectMapper om;

    public AiReportNarrator(AiGateway gateway, ObjectMapper om) {
        this.gateway = gateway;
        this.om = om;
    }

    @Override
    public Optional<String> narrate(Long teamId, AppUser actor, String kind, String factsMarkdown) {
        if (!gateway.featureAvailable(teamId, "summarize")) return Optional.empty();
        try {
            ObjectNode payload = om.createObjectNode();
            payload.put("kind", kind);
            payload.put("facts", factsMarkdown);
            String payloadText = om.writeValueAsString(payload);
            for (int attempt = 0; attempt < 2; attempt++) {
                JsonNode json = gateway.chatJson(teamId, actor, "summarize", payloadText, null);
                String narrative = json.path("narrative").asText("");
                if (!narrative.isBlank() && consistent(narrative, factsMarkdown)) return Optional.of(narrative);
                log.warn("AI 叙述校验不一致，丢弃重跑 attempt={}", attempt);
            }
        } catch (Exception e) {
            log.warn("AI 叙述不可用，回退程序模板：{}", e.getMessage());
        }
        return Optional.empty();
    }

    /** 编号与数字只能来自事实表（子集校验）。 */
    static boolean consistent(String narrative, String facts) {
        Set<String> factCodes = collect(CODE, facts, true);
        for (String c : collect(CODE, narrative, true)) if (!factCodes.contains(c)) return false;
        Set<String> factNums = collect(NUMBER, facts, false);
        for (String n : collect(NUMBER, narrative, false)) if (!factNums.contains(n)) return false;
        return true;
    }

    private static Set<String> collect(Pattern p, String text, boolean upper) {
        Set<String> out = new HashSet<>();
        Matcher m = p.matcher(text == null ? "" : text);
        while (m.find()) out.add(upper ? m.group().toUpperCase() : m.group());
        return out;
    }
}
