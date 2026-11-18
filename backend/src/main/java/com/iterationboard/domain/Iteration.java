package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "iteration")
public class Iteration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @Column(nullable = false, length = 100)
    private String name;
    @Column
    private String goal;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(nullable = false, length = 16)
    private String status = "PLANNING";
    @Column(name = "closed_at")
    private Instant closedAt;

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getGoal() { return goal; }
    public void setGoal(String v) { goal = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { endDate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { closedAt = v; }
}
