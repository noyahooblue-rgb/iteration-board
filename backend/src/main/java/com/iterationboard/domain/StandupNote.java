package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** 站会备注：一组一天一条。 */
@Entity
@Table(name = "standup_note", uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "note_date"}))
public class StandupNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @Column(name = "note_date", nullable = false)
    private LocalDate noteDate;
    @Column(nullable = false)
    private String content = "";
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "updated_by")
    private AppUser updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public LocalDate getNoteDate() { return noteDate; }
    public void setNoteDate(LocalDate v) { noteDate = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public AppUser getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(AppUser v) { updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
