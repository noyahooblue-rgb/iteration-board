package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 反馈图片：后端此前无独立贴图存储，图片以 Base64 存库（单张 ≤5MB、每条 ≤5 张，写入时校验类型与大小）。 */
@Entity
@Table(name = "feedback_image")
public class FeedbackImage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "feedback_id", nullable = false)
    private Feedback feedback;
    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "content_base64", nullable = false)
    private String contentBase64;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Feedback getFeedback() { return feedback; }
    public void setFeedback(Feedback v) { feedback = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { fileName = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { contentType = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { sizeBytes = v; }
    public String getContentBase64() { return contentBase64; }
    public void setContentBase64(String v) { contentBase64 = v; }
    public Instant getCreatedAt() { return createdAt; }
}
