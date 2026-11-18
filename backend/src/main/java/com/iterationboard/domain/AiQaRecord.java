package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** AI 问答记录。 */
@Entity
@Table(name = "ai_qa_record")
public class AiQaRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(nullable = false)
    private String question;
    @Column(nullable = false)
    private String answer = "";
    @Column(nullable = false)
    private boolean found;
    @Column(name = "refs_json")
    private String refsJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AiQaRecord() {}
    public AiQaRecord(Team team, AppUser user, String question, String answer, boolean found, String refsJson) {
        this.team = team; this.user = user; this.question = question;
        this.answer = answer; this.found = found; this.refsJson = refsJson;
    }

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public AppUser getUser() { return user; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public boolean isFound() { return found; }
    public String getRefsJson() { return refsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
