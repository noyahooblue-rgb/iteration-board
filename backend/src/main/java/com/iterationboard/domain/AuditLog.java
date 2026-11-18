package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 审计日志（Q6：本轮建表+写入点，读取界面 M5）。只写不改。 */
@Entity
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "team_id")
    private Long teamId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_id", nullable = false)
    private AppUser actor;
    @Column(nullable = false, length = 64)
    private String action;
    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;
    @Column(name = "target_id")
    private Long targetId;
    @Column
    private String detail;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AuditLog() {}
    public AuditLog(Long teamId, AppUser actor, String action, String targetType, Long targetId, String detail) {
        this.teamId = teamId; this.actor = actor; this.action = action;
        this.targetType = targetType; this.targetId = targetId; this.detail = detail;
    }

    public Long getId() { return id; }
    public Long getTeamId() { return teamId; }
    public AppUser getActor() { return actor; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
