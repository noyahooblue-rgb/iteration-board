package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/** 导入批次明细：CREATE 新建 / UPDATE 更新外部字段（保存更新前快照，供整批撤销回滚）。 */
@Entity
@Table(name = "import_batch_item")
public class ImportBatchItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "batch_id", nullable = false)
    private ImportBatch batch;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id", nullable = false)
    private Item item;
    @Column(nullable = false, length = 16)
    private String action;
    @Column(name = "prev_title", length = 200)
    private String prevTitle;
    @Column(name = "prev_description")
    private String prevDescription;
    @Column(name = "prev_due_date")
    private LocalDate prevDueDate;

    public ImportBatchItem() {}
    public ImportBatchItem(ImportBatch batch, Item item, String action,
                           String prevTitle, String prevDescription, LocalDate prevDueDate) {
        this.batch = batch; this.item = item; this.action = action;
        this.prevTitle = prevTitle; this.prevDescription = prevDescription; this.prevDueDate = prevDueDate;
    }

    public Long getId() { return id; }
    public ImportBatch getBatch() { return batch; }
    public Item getItem() { return item; }
    public String getAction() { return action; }
    public String getPrevTitle() { return prevTitle; }
    public String getPrevDescription() { return prevDescription; }
    public LocalDate getPrevDueDate() { return prevDueDate; }
}
