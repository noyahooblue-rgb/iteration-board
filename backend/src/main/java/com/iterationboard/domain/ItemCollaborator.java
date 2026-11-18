package com.iterationboard.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "item_collaborator", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "user_id"}))
public class ItemCollaborator {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    public ItemCollaborator() {}
    public ItemCollaborator(Item item, AppUser user) { this.item = item; this.user = user; }
    public Long getId() { return id; }
    public Item getItem() { return item; }
    public AppUser getUser() { return user; }
}
