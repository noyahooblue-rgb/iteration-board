package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 下发批次（修订一第 5/7 节）：每人一份生成的同批事项归属。 */
@Entity
@Table(name = "dispatch_batch")
public class DispatchBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "creator_id", nullable = false)
    private AppUser creator;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(name = "item_type", nullable = false, length = 16)
    private String itemType;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public AppUser getCreator() { return creator; }
    public void setCreator(AppUser v) { creator = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getItemType() { return itemType; }
    public void setItemType(String v) { itemType = v; }
    public Instant getCreatedAt() { return createdAt; }
}
