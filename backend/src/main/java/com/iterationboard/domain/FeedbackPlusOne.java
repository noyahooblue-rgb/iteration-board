package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 反馈 +1（唯一约束 feedback_id+user_id 防重复）。 */
@Entity
@Table(name = "feedback_plus_one",
        uniqueConstraints = @UniqueConstraint(columnNames = {"feedback_id", "user_id"}))
public class FeedbackPlusOne {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "feedback_id", nullable = false)
    private Feedback feedback;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public FeedbackPlusOne() {}
    public FeedbackPlusOne(Feedback feedback, AppUser user) { this.feedback = feedback; this.user = user; }
    public Long getId() { return id; }
    public Feedback getFeedback() { return feedback; }
    public AppUser getUser() { return user; }
    public Instant getCreatedAt() { return createdAt; }
}
