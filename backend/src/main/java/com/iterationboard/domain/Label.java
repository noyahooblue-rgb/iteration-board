package com.iterationboard.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "label", uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "name"}))
public class Label {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "team_id", nullable = false)
    private Team team;
    @Column(nullable = false, length = 32)
    private String name;
    @Column(length = 16)
    private String color;

    public Long getId() { return id; }
    public Team getTeam() { return team; }
    public void setTeam(Team v) { team = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getColor() { return color; }
    public void setColor(String v) { color = v; }
}
