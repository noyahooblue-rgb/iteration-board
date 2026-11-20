package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 通知：M2 起写入关键事件通知（婉拒/加入/任务待验收/批次催更），通知中心 UI 属 M3。 */
@Entity
@Table(name = "notification")
public class Notification {
    public Notification() {}
    public Notification(AppUser recipient, String type, Item item, AppUser actor, String content) {
        this.recipient = recipient; this.type = type; this.item = item; this.actor = actor; this.content = content;
    }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "recipient_id", nullable = false)
    private AppUser recipient;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id")
    private Team team;
    @Column(nullable = false, length = 32)
    private String type;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id")
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_id")
    private AppUser actor;
    @Column(length = 500)
    private String content;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public AppUser getRecipient() { return recipient; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public String getType() { return type; }
    public Item getItem() { return item; }
    public AppUser getActor() { return actor; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant v) { readAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
