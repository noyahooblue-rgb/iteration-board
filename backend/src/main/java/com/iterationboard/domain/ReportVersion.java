package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 报告历史版本。 */
@Entity
@Table(name = "report_version")
public class ReportVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "report_id", nullable = false)
    private Report report;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false)
    private String content = "";
    @Column(name = "edited_by_human", nullable = false)
    private boolean editedByHuman;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public ReportVersion() {}
    public ReportVersion(Report report) {
        this.report = report;
        this.title = report.getTitle();
        this.content = report.getContent();
        this.editedByHuman = report.isEditedByHuman();
    }

    public Long getId() { return id; }
    public Report getReport() { return report; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public boolean isEditedByHuman() { return editedByHuman; }
    public Instant getCreatedAt() { return createdAt; }
}
