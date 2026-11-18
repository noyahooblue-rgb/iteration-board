package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 小组 AI 逐项开关。无行 = 全部默认开启。 */
@Entity
@Table(name = "team_ai_settings")
public class TeamAiSettings {
    @Id @Column(name = "team_id")
    private Long teamId;
    @Column(name = "paste_import", nullable = false)
    private boolean pasteImport = true;
    @Column(name = "quick_update", nullable = false)
    private boolean quickUpdate = true;
    @Column(name = "split_stories", nullable = false)
    private boolean splitStories = true;
    @Column(nullable = false)
    private boolean summarize = true;
    @Column(name = "risk_summary", nullable = false)
    private boolean riskSummary = true;
    @Column(nullable = false)
    private boolean qa = true;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public TeamAiSettings() {}
    public TeamAiSettings(Long teamId) { this.teamId = teamId; }

    public boolean enabledFor(String feature) {
        return switch (feature) {
            case "paste-import" -> pasteImport;
            case "quick-update" -> quickUpdate;
            case "split-stories" -> splitStories;
            case "summarize" -> summarize;
            case "risk-summary" -> riskSummary;
            case "qa" -> qa;
            default -> true;
        };
    }

    public void setFor(String feature, boolean v) {
        switch (feature) {
            case "paste-import" -> pasteImport = v;
            case "quick-update" -> quickUpdate = v;
            case "split-stories" -> splitStories = v;
            case "summarize" -> summarize = v;
            case "risk-summary" -> riskSummary = v;
            case "qa" -> qa = v;
            default -> { }
        }
        updatedAt = Instant.now();
    }

    public Long getTeamId() { return teamId; }
    public boolean isPasteImport() { return pasteImport; }
    public void setPasteImport(boolean v) { pasteImport = v; }
    public boolean isQuickUpdate() { return quickUpdate; }
    public void setQuickUpdate(boolean v) { quickUpdate = v; }
    public boolean isSplitStories() { return splitStories; }
    public void setSplitStories(boolean v) { splitStories = v; }
    public boolean isSummarize() { return summarize; }
    public void setSummarize(boolean v) { summarize = v; }
    public boolean isRiskSummary() { return riskSummary; }
    public void setRiskSummary(boolean v) { riskSummary = v; }
    public boolean isQa() { return qa; }
    public void setQa(boolean v) { qa = v; }
    public Instant getUpdatedAt() { return updatedAt; }
}
