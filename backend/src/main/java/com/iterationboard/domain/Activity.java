package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "activity")
public class Activity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_id", nullable = false)
    private AppUser actor;
    @Column(nullable = false, length = 32)
    private String action;
    @Column(name = "from_status", length = 16)
    private String fromStatus;
    @Column(name = "to_status", length = 16)
    private String toStatus;
    @Column
    private String detail;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Activity() {}
    public Activity(Item item, AppUser actor, String action, String fromStatus, String toStatus, String detail) {
        this.item = item; this.actor = actor; this.action = action;
        this.fromStatus = fromStatus; this.toStatus = toStatus; this.detail = detail;
    }
    public Long getId() { return id; }
    public Item getItem() { return item; }
    public AppUser getActor() { return actor; }
    public String getAction() { return action; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
