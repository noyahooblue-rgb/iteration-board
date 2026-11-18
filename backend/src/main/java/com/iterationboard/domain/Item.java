package com.iterationboard.domain;

import com.iterationboard.domain.Enums.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "item")
public class Item {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @Column(nullable = false)
    private long seq;
    @Column(nullable = false, length = 32)
    private String code;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ItemType type;
    @Column(nullable = false, length = 200)
    private String title;
    @Column
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ItemStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 4)
    private Priority priority = Priority.P2;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "creator_id", nullable = false)
    private AppUser creator;
    @Enumerated(EnumType.STRING) @Column(name = "story_category", length = 16)
    private StoryCategory storyCategory;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "po_id")
    private AppUser po;
    @Column(name = "batch_id")
    private Long batchId;
    @Column(name = "assigned_directly", nullable = false)
    private boolean assignedDirectly;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "acceptor_id")
    private AppUser acceptor;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id")
    private Item parent;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "iteration_id")
    private Iteration iteration;
    @Column(name = "backlog_order")
    private Long backlogOrder;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column
    private BigDecimal estimate;
    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;
    @Column(nullable = false)
    private boolean blocked;
    @Column(name = "block_reason", length = 500)
    private String blockReason;
    @Column(name = "blocked_at")
    private Instant blockedAt;
    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt = Instant.now();
    @Column(name = "last_progress_at")
    private Instant lastProgressAt;
    @Column(name = "nudge_count", nullable = false)
    private int nudgeCount;
    @Column(nullable = false, length = 16)
    private String source = "MANUAL";
    @Column(name = "external_system", length = 64)
    private String externalSystem;
    @Column(name = "external_id", length = 64)
    private String externalId;
    @Column(name = "external_url", length = 500)
    private String externalUrl;
    @Column(name = "import_batch_id")
    private Long importBatchId;
    @Column(name = "archived_at")
    private Instant archivedAt;
    @Column(name = "external_closed", nullable = false)
    private boolean externalClosed;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Column(name = "deleted_by_id")
    private Long deletedById;
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
    public long getSeq() { return seq; }
    public void setSeq(long v) { seq = v; }
    public String getCode() { return code; }
    public void setCode(String v) { code = v; }
    public ItemType getType() { return type; }
    public void setType(ItemType v) { type = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public ItemStatus getStatus() { return status; }
    public void setStatus(ItemStatus v) { status = v; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority v) { priority = v; }
    public AppUser getCreator() { return creator; }
    public void setCreator(AppUser v) { creator = v; }
    public StoryCategory getStoryCategory() { return storyCategory; }
    public void setStoryCategory(StoryCategory v) { storyCategory = v; }
    public AppUser getPo() { return po; }
    public void setPo(AppUser v) { po = v; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long v) { batchId = v; }
    public boolean isAssignedDirectly() { return assignedDirectly; }
    public void setAssignedDirectly(boolean v) { assignedDirectly = v; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { sortOrder = v; }
    public Long getBacklogOrder() { return backlogOrder; }
    public void setBacklogOrder(Long v) { backlogOrder = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getExternalSystem() { return externalSystem; }
    public void setExternalSystem(String v) { externalSystem = v; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String v) { externalId = v; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String v) { externalUrl = v; }
    public Long getImportBatchId() { return importBatchId; }
    public void setImportBatchId(Long v) { importBatchId = v; }
    public boolean isExternalClosed() { return externalClosed; }
    public void setExternalClosed(boolean v) { externalClosed = v; }
    public Instant getArchivedAt() { return archivedAt; }
    public void setDeletedAt(Instant v) { deletedAt = v; }
    public Long getDeletedById() { return deletedById; }
    public void setDeletedById(Long v) { deletedById = v; }
    public AppUser getAcceptor() { return acceptor; }
    public void setAcceptor(AppUser v) { acceptor = v; }
    public Item getParent() { return parent; }
    public void setParent(Item v) { parent = v; }
    public Iteration getIteration() { return iteration; }
    public void setIteration(Iteration v) { iteration = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { dueDate = v; }
    public BigDecimal getEstimate() { return estimate; }
    public void setEstimate(BigDecimal v) { estimate = v; }
    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int v) { progressPercent = v; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean v) { blocked = v; }
    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String v) { blockReason = v; }
    public Instant getBlockedAt() { return blockedAt; }
    public void setBlockedAt(Instant v) { blockedAt = v; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
    public void setStatusChangedAt(Instant v) { statusChangedAt = v; }
    public Instant getLastProgressAt() { return lastProgressAt; }
    public void setLastProgressAt(Instant v) { lastProgressAt = v; }
    public int getNudgeCount() { return nudgeCount; }
    public void setNudgeCountReset() { this.nudgeCount = 0; }
    public void incrementNudgeCount() { this.nudgeCount++; }
    public int getVersion() { return version; }
    public void setVersion(int v) { version = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
