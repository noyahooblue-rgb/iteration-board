package com.iterationboard.ai;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI 兼容客户端：Spring/JDK 自带 HttpClient 直连 /chat/completions，不引新依赖。 */
@Component
public class LiveAiClient {
    public record Result(String content, String model, Integer promptTokens, Integer completionTokens) {}

    private final AiProperties props;
    private final ObjectMapper objectMapper;

    public LiveAiClient(AiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public Result chat(String systemPrompt, String userText, String imageDataUrl, boolean vision) throws Exception {
        String model = vision ? props.getVisionModel() : props.getTextModel();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        if (imageDataUrl != null) {
            ArrayNode parts = usr.putArray("content");
            ObjectNode t = parts.addObject();
            t.put("type", "text");
            t.put("text", userText);
            ObjectNode im = parts.addObject();
            im.put("type", "image_url");
            im.putObject("image_url").put("url", imageDataUrl);
        } else {
            usr.put("content", userText);
        }
        String base = props.getBaseUrl().replaceAll("/+$", "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("AI 服务 HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("模型返回空内容");
        }
        JsonNode usage = root.path("usage");
        return new Result(content.asText(), model,
                usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
    }
}
