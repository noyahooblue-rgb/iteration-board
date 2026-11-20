package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 意见反馈（修订三第四节，平台级，不属于任何小组）。状态：NEW → ANALYZING → ACCEPTED / REJECTED → RELEASED。 */
@Entity
@Table(name = "feedback")
public class Feedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "submitter_id", nullable = false)
    private AppUser submitter;
    @Column(nullable = false, length = 16)
    private String type;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false)
    private String description;
    @Column(name = "page_url", length = 500)
    private String pageUrl;
    @Column(name = "app_version", length = 32)
    private String appVersion;
    @Column(name = "browser_info", length = 500)
    private String browserInfo;
    @Column(nullable = false, length = 16)
    private String status = "NEW";
    @Column(name = "planned_version", length = 32)
    private String plannedVersion;
    @Column(name = "reject_reason", length = 500)
    private String rejectReason;
    @Column(name = "released_version", length = 32)
    private String releasedVersion;
    @Column(name = "official_reply")
    private String officialReply;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public AppUser getSubmitter() { return submitter; }
    public void setSubmitter(AppUser v) { submitter = v; }
    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getPageUrl() { return pageUrl; }
    public void setPageUrl(String v) { pageUrl = v; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String v) { appVersion = v; }
    public String getBrowserInfo() { return browserInfo; }
    public void setBrowserInfo(String v) { browserInfo = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getPlannedVersion() { return plannedVersion; }
    public void setPlannedVersion(String v) { plannedVersion = v; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String v) { rejectReason = v; }
    public String getReleasedVersion() { return releasedVersion; }
    public void setReleasedVersion(String v) { releasedVersion = v; }
    public String getOfficialReply() { return officialReply; }
    public void setOfficialReply(String v) { officialReply = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
