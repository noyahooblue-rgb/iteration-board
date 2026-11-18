package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 同步日志。 */
@Entity
@Table(name = "sync_log")
public class SyncLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "adapter_id", nullable = false)
    private IntegrationAdapter adapter;
    @Column(name = "team_id", nullable = false)
    private Long teamId;
    @Column(name = "actor_id")
    private Long actorId;
    @Column(name = "created_count", nullable = false)
    private int createdCount;
    @Column(name = "updated_count", nullable = false)
    private int updatedCount;
    @Column(name = "failed_count", nullable = false)
    private int failedCount;
    @Column(length = 1000)
    private String message;
    @Column(nullable = false)
    private boolean success;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public SyncLog() {}
    public SyncLog(IntegrationAdapter adapter, Long teamId, Long actorId, int createdCount,
                   int updatedCount, int failedCount, String message, boolean success) {
        this.adapter = adapter; this.teamId = teamId; this.actorId = actorId;
        this.createdCount = createdCount; this.updatedCount = updatedCount; this.failedCount = failedCount;
        this.message = message; this.success = success;
    }

    public Long getId() { return id; }
    public IntegrationAdapter getAdapter() { return adapter; }
    public Long getTeamId() { return teamId; }
    public Long getActorId() { return actorId; }
    public int getCreatedCount() { return createdCount; }
    public int getUpdatedCount() { return updatedCount; }
    public int getFailedCount() { return failedCount; }
    public String getMessage() { return message; }
    public boolean isSuccess() { return success; }
    public Instant getCreatedAt() { return createdAt; }
}
