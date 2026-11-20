package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feedback_comment")
public class FeedbackComment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "feedback_id", nullable = false)
    private Feedback feedback;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_id", nullable = false)
    private AppUser author;
    @Column(nullable = false)
    private String content;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Feedback getFeedback() { return feedback; }
    public void setFeedback(Feedback v) { feedback = v; }
    public AppUser getAuthor() { return author; }
    public void setAuthor(AppUser v) { author = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public Instant getCreatedAt() { return createdAt; }
}
