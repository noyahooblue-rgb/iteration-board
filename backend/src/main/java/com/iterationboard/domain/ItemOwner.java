package com.iterationboard.domain;

import jakarta.persistence.*;

/** 事项负责人（共同负责，修订一第 5 节）：一对多关联表 item_owner。 */
@Entity
@Table(name = "item_owner", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "user_id"}))
public class ItemOwner {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    public ItemOwner() {}
    public ItemOwner(Item item, AppUser user) { this.item = item; this.user = user; }
    public Long getId() { return id; }
    public Item getItem() { return item; }
    public AppUser getUser() { return user; }
}
