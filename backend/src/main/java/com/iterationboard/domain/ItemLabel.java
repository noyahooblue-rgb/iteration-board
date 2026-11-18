package com.iterationboard.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "item_label", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "label_id"}))
public class ItemLabel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "label_id", nullable = false)
    private Label label;

    public ItemLabel() {}
    public ItemLabel(Item item, Label label) { this.item = item; this.label = label; }
    public Label getLabel() { return label; }
    public Item getItem() { return item; }
}
