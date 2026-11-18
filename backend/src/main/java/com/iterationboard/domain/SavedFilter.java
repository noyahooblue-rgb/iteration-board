package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 保存的筛选。 */
@Entity
@Table(name = "saved_filter")
public class SavedFilter {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    @Column(nullable = false, length = 64)
    private String name;
    @Column(name = "criteria_json", nullable = false)
    private String criteriaJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser v) { user = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getCriteriaJson() { return criteriaJson; }
    public void setCriteriaJson(String v) { criteriaJson = v; }
    public Instant getCreatedAt() { return createdAt; }
}
