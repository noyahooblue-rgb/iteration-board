package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 催更记录：同一人对同一事项每天一次的判定依据。 */
@Entity
@Table(name = "nudge")
public class Nudge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "nudger_id", nullable = false)
    private AppUser nudger;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "target_id", nullable = false)
    private AppUser target;
    @Column(length = 16)
    private String response;
    @Column(name = "responded_at")
    private Instant respondedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Nudge() {}
    public Nudge(Item item, AppUser nudger, AppUser target) { this.item = item; this.nudger = nudger; this.target = target; }
    public Long getId() { return id; }
    public Item getItem() { return item; }
    public AppUser getNudger() { return nudger; }
    public AppUser getTarget() { return target; }
    public String getResponse() { return response; }
    public void setResponse(String v) { response = v; }
    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant v) { respondedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
