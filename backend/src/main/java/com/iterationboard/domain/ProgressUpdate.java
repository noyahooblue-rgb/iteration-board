package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "progress_update")
public class ProgressUpdate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_id", nullable = false)
    private AppUser author;
    @Column(nullable = false)
    private String text;
    @Column
    private Integer percent;
    @Column(nullable = false)
    private boolean blocked;
    @Column(name = "block_reason", length = 500)
    private String blockReason;
    @Column(name = "next_step", length = 500)
    private String nextStep;
    @Column(nullable = false, length = 16)
    private String kind = "NORMAL";
    @Column(nullable = false, length = 16)
    private String channel = "MANUAL";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Item getItem() { return item; }
    public void setItem(Item v) { item = v; }
    public AppUser getAuthor() { return author; }
    public void setAuthor(AppUser v) { author = v; }
    public String getText() { return text; }
    public void setText(String v) { text = v; }
    public Integer getPercent() { return percent; }
    public void setPercent(Integer v) { percent = v; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean v) { blocked = v; }
    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String v) { blockReason = v; }
    public String getNextStep() { return nextStep; }
    public void setNextStep(String v) { nextStep = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }
    public Instant getCreatedAt() { return createdAt; }
}
