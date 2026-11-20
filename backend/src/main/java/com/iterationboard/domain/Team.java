package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "team")
public class Team {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String name;
    @Column(nullable = false, length = 5)
    private String code;
    @Column(length = 500)
    private String description;
    @Column(name = "invite_code", nullable = false, unique = true, length = 16)
    private String inviteCode;
    @Column(name = "join_approval", nullable = false)
    private boolean joinApproval = true;
    @Column(name = "iteration_weeks", nullable = false)
    private int iterationWeeks = 2;
    @Column(name = "estimate_unit", nullable = false, length = 8)
    private String estimateUnit = "point";
    @Column(name = "theme_color", nullable = false, length = 16)
    private String themeColor = "blue";
    @Column(name = "settings_json")
    private String settingsJson;
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";
    @Column(name = "item_seq", nullable = false)
    private long itemSeq;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getCode() { return code; }
    public void setCode(String v) { code = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String v) { inviteCode = v; }
    public boolean isJoinApproval() { return joinApproval; }
    public void setJoinApproval(boolean v) { joinApproval = v; }
    public int getIterationWeeks() { return iterationWeeks; }
    public void setIterationWeeks(int v) { iterationWeeks = v; }
    public String getEstimateUnit() { return estimateUnit; }
    public void setEstimateUnit(String v) { estimateUnit = v; }
    public String getThemeColor() { return themeColor; }
    public void setThemeColor(String v) { themeColor = v; }
    public String getSettingsJson() { return settingsJson; }
    public void setSettingsJson(String v) { settingsJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant v) { deletedAt = v; }
    public long getItemSeq() { return itemSeq; }
    public void setItemSeq(long v) { itemSeq = v; }
    public Instant getCreatedAt() { return createdAt; }
}
