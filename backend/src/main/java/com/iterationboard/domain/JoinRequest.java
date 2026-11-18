package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "join_request")
public class JoinRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(nullable = false, length = 16)
    private String status = "PENDING";
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "approver_id")
    private AppUser approver;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "decided_at")
    private Instant decidedAt;

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser v) { user = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public void setApprover(AppUser v) { approver = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setDecidedAt(Instant v) { decidedAt = v; }
}
