package com.iterationboard.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "checklist_entry")
public class ChecklistEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @Column(nullable = false, length = 500)
    private String text;
    @Column(nullable = false)
    private boolean done;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() { return id; }
    public Item getItem() { return item; }
    public void setItem(Item v) { item = v; }
    public String getText() { return text; }
    public void setText(String v) { text = v; }
    public boolean isDone() { return done; }
    public void setDone(boolean v) { done = v; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int v) { sortOrder = v; }
}
