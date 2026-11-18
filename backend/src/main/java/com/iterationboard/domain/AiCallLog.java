package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI 调用日志。 */
@Entity
@Table(name = "ai_call_log")
public class AiCallLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "team_id")
    private Long teamId;
    @Column(name = "user_id")
    private Long userId;
    @Column(nullable = false, length = 32)
    private String feature;
    @Column(length = 128)
    private String model;
    @Column(name = "duration_ms", nullable = false)
    private long durationMs;
    @Column(name = "prompt_tokens")
    private Integer promptTokens;
    @Column(name = "completion_tokens")
    private Integer completionTokens;
    @Column(nullable = false)
    private boolean success;
    @Column(length = 500)
    private String error;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AiCallLog() {}
    public AiCallLog(Long teamId, Long userId, String feature, String model, long durationMs,
                     Integer promptTokens, Integer completionTokens, boolean success, String error) {
        this.teamId = teamId; this.userId = userId; this.feature = feature; this.model = model;
        this.durationMs = durationMs; this.promptTokens = promptTokens; this.completionTokens = completionTokens;
        this.success = success; this.error = error;
    }

    public Long getId() { return id; }
    public Long getTeamId() { return teamId; }
    public Long getUserId() { return userId; }
    public String getFeature() { return feature; }
    public String getModel() { return model; }
    public long getDurationMs() { return durationMs; }
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public boolean isSuccess() { return success; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
}
