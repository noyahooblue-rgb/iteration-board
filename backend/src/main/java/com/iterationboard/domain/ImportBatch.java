package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 导入批次：来源 AI_PASTE / FILE_MAPPING / ADAPTER；24 小时内可整批撤销。 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "creator_id", nullable = false)
    private AppUser creator;
    @Column(nullable = false, length = 32)
    private String source;
    @Column(nullable = false)
    private int total;
    @Column(name = "created_count", nullable = false)
    private int createdCount;
    @Column(name = "updated_count", nullable = false)
    private int updatedCount;
    @Column(name = "undone_at")
    private Instant undoneAt;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "undone_by")
    private AppUser undoneBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public ImportBatch() {}
    public ImportBatch(Team team, AppUser creator, String source) {
        this.team = team; this.creator = creator; this.source = source;
    }

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public AppUser getCreator() { return creator; }
    public String getSource() { return source; }
    public int getTotal() { return total; }
    public void setTotal(int v) { total = v; }
    public int getCreatedCount() { return createdCount; }
    public void setCreatedCount(int v) { createdCount = v; }
    public int getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(int v) { updatedCount = v; }
    public Instant getUndoneAt() { return undoneAt; }
    public void setUndoneAt(Instant v) { undoneAt = v; }
    public AppUser getUndoneBy() { return undoneBy; }
    public void setUndoneBy(AppUser v) { undoneBy = v; }
    public Instant getCreatedAt() { return createdAt; }
}
