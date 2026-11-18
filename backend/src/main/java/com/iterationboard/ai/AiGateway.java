package com.iterationboard.ai;

import com.iterationboard.common.ApiException;
import com.iterationboard.domain.AiCallLog;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.TeamAiSettings;
import com.iterationboard.repo.AiCallLogRepo;
import com.iterationboard.repo.TeamAiSettingsRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * AI 网关：可用性判定（平台总开关 + 小组逐项开关 + 模式配置）、
 * 调用日志、JSON 结构校验失败自动重试 1 次，再失败抛 AI_UNAVAILABLE（前端提示手工方式）。
 * 模型只出建议不触发操作；写入一律由编排服务经既有服务完成。
 */
@Service
public class AiGateway {
    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);
    public static final List<String> FEATURES = List.of(
            "paste-import", "quick-update", "summarize", "split-stories", "risk-summary", "qa");

    private final AiProperties props;
    private final PromptLoader promptLoader;
    private final TeamAiSettingsRepo teamSettingsRepo;
    private final AiCallLogRepo callLogRepo;
    private final LiveAiClient liveClient;
    private final MockAiResponder mockResponder;
    private final ObjectMapper objectMapper;

    public AiGateway(AiProperties props, PromptLoader promptLoader, TeamAiSettingsRepo teamSettingsRepo,
                     AiCallLogRepo callLogRepo, LiveAiClient liveClient, MockAiResponder mockResponder,
                     ObjectMapper objectMapper) {
        this.props = props;
        this.promptLoader = promptLoader;
        this.teamSettingsRepo = teamSettingsRepo;
        this.callLogRepo = callLogRepo;
        this.liveClient = liveClient;
        this.mockResponder = mockResponder;
        this.objectMapper = objectMapper;
    }

    public AiProperties props() { return props; }

    public TeamAiSettings teamSettings(Long teamId) {
        return teamSettingsRepo.findById(teamId).orElseGet(() -> new TeamAiSettings(teamId));
    }

    /** 平台+小组开关合并后的单项可用状态（不含 live 配置完整性）。 */
    public boolean featureEnabled(Long teamId, String feature) {
        return props.isPlatformEnabled() && teamSettings(teamId).enabledFor(feature);
    }

    /** 功能实际可用：开关开 + （mock 或 live 已配齐）。 */
    public boolean featureAvailable(Long teamId, String feature) {
        return featureEnabled(teamId, feature) && (props.isMock() || props.liveConfigured());
    }

    public boolean visionAvailable(Long teamId) {
        return props.isPlatformEnabled() && props.visionConfigured()
                && (props.isMock() || props.liveConfigured());
    }

    public static ApiException unavailable(String msg) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", msg);
    }

    /** 要求功能可用：开关关 → 403 AI_FEATURE_DISABLED；配置缺失 → 503 AI_UNAVAILABLE。 */
    public void requireAvailable(Long teamId, String feature) {
        if (!props.isPlatformEnabled() || !teamSettings(teamId).enabledFor(feature)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI_FEATURE_DISABLED", "该 AI 功能未开启");
        }
        if (!props.isMock() && !props.liveConfigured()) {
            throw unavailable("AI 服务未配置完整（地址/密钥/文本模型），请使用手工方式");
        }
    }

    /**
     * 调用模型并要求返回 JSON。userPayload 为完整用户消息文本（提示词模板由调用方拼好数据分隔块）。
     * imageDataUrl 非空时走识图模型。结构校验失败自动重试 1 次。
     */
    public JsonNode chatJson(Long teamId, AppUser user, String feature, String userPayload, String imageDataUrl) {
        requireAvailable(teamId, feature);
        if (imageDataUrl != null && !visionAvailable(teamId)) {
            throw unavailable("识图模型未配置，无法解析图片，请改用文字或文件方式");
        }
        String system = promptLoader.prompt(feature);
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            long start = System.nanoTime();
            try {
                LiveAiClient.Result res = props.isMock()
                        ? mockResponder.respond(feature, system, userPayload, imageDataUrl)
                        : liveClient.chat(system, userPayload, imageDataUrl, imageDataUrl != null);
                long ms = (System.nanoTime() - start) / 1_000_000;
                JsonNode json = parseJson(res.content());
                writeLog(teamId, user, feature, res.model(), ms, res.promptTokens(), res.completionTokens(), true, null);
                return json;
            } catch (Exception e) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                last = e;
                writeLog(teamId, user, feature, props.isMock() ? "mock" : props.getTextModel(), ms, null, null, false,
                        trim(e.getMessage()));
                log.warn("AI 调用失败 feature={} attempt={} err={}", feature, attempt, e.getMessage());
            }
        }
        throw unavailable("AI 调用失败或返回结构不合规，请使用手工方式" + (last == null ? "" : "（" + trim(last.getMessage()) + "）"));
    }

    private JsonNode parseJson(String content) {
        if (content == null || content.isBlank()) throw new IllegalStateException("模型返回空内容");
        String s = content.strip();
        int i = s.indexOf('{'), j = s.lastIndexOf('}');
        if (i < 0 || j <= i) throw new IllegalStateException("模型返回不是 JSON 对象");
        try {
            return objectMapper.readTree(s.substring(i, j + 1));
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败：" + e.getMessage());
        }
    }

    /**
     * 写调用日志（D-1 修复口径）：与功能调用同事务写入，不另开事务。
     * 根因复盘：原 writeLog 标了 REQUIRES_NEW 却与网关同 Bean 自调用，注解经代理不生效，
     * INSERT 实际跑在调用方的只读事务里失败并把外层事务标成 rollback-only，功能端点整体 500。
     * 两条候选路线中选「同事务」而非「独立 REQUIRES_NEW 事务」：ai_call_log.team_id 有 FK，
     * 独立事务看不到调用方尚未提交的数据（集成测试的事务内新建小组即触发 FK 失败、日志丢失）；
     * 同事务则日志与调用同提交同回滚、一致性天然成立。前提是调用方不得以只读事务包裹网关——
     * AiFeatureService 的功能方法与 ReportService.preview 已全部改为可写事务（见各处注释）。
     * save 不立即 flush：异常只在提交期暴露，不会在此把异常毒化回功能逻辑。
     */
    public void writeLog(Long teamId, AppUser user, String feature, String model, long durationMs,
                         Integer promptTokens, Integer completionTokens, boolean success, String error) {
        try {
            callLogRepo.save(new AiCallLog(teamId, user == null ? null : user.getId(), feature, model,
                    durationMs, promptTokens, completionTokens, success, error));
        } catch (Exception e) {
            log.warn("AI 调用日志写入失败：{}", e.getMessage());
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
