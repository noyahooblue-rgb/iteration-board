package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** 报告：站会摘要/周报/迭代总结/个人周报/风险摘要，Markdown 正文，可编辑。 */
@Entity
@Table(name = "report")
public class Report {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @Column(nullable = false, length = 32)
    private String kind;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(name = "period_start")
    private LocalDate periodStart;
    @Column(name = "period_end")
    private LocalDate periodEnd;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "owner_id")
    private AppUser owner;
    @Column(nullable = false)
    private String content = "";
    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;
    @Column(nullable = false)
    private boolean edited;
    //：人工编辑标记（与 edited 同步维护，edited 为 M3 既有字段保留兼容）
    @Column(name = "edited_by_human", nullable = false)
    private boolean editedByHuman;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "deleted_by_id")
    private Long deletedById;
    @Column(name = "last_generated_at")
    private Instant lastGeneratedAt;
    @Column(name = "iteration_id")
    private Long iterationId;
    @Version @Column(nullable = false)
    private int version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate v) { periodStart = v; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate v) { periodEnd = v; }
    public AppUser getOwner() { return owner; }
    public void setOwner(AppUser v) { owner = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public boolean isAiGenerated() { return aiGenerated; }
    public void setAiGenerated(boolean v) { aiGenerated = v; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean v) { edited = v; editedByHuman = v; }
    public boolean isEditedByHuman() { return editedByHuman; }
    public void setEditedByHuman(boolean v) { editedByHuman = v; edited = v; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant v) { deletedAt = v; }
    public Long getDeletedById() { return deletedById; }
    public void setDeletedById(Long v) { deletedById = v; }
    public Instant getLastGeneratedAt() { return lastGeneratedAt; }
    public void setLastGeneratedAt(Instant v) { lastGeneratedAt = v; }
    public Long getIterationId() { return iterationId; }
    public void setIterationId(Long v) { iterationId = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { version = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
