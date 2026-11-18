package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** 迭代关键日期：封版 FREEZE / 投产 RELEASE / 评审 REVIEW / 其他 OTHER。 */
@Entity
@Table(name = "key_date")
public class KeyDate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "iteration_id", nullable = false)
    private Iteration iteration;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(name = "key_date", nullable = false)
    private LocalDate date;
    @Column(nullable = false, length = 16)
    private String category = "OTHER";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Iteration getIteration() { return iteration; }
    public void setIteration(Iteration v) { iteration = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate v) { date = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { category = v; }
    public Instant getCreatedAt() { return createdAt; }
}
