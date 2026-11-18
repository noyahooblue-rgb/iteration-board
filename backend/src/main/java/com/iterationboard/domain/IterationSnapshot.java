package com.iterationboard.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 燃尽快照：每晚记录一次剩余估算/件数与完成件数。 */
@Entity
@Table(name = "iteration_snapshot", uniqueConstraints = @UniqueConstraint(columnNames = {"iteration_id", "snapshot_date"}))
public class IterationSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "iteration_id", nullable = false)
    private Iteration iteration;
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;
    @Column(name = "remaining_estimate", nullable = false)
    private BigDecimal remainingEstimate = BigDecimal.ZERO;
    @Column(name = "remaining_count", nullable = false)
    private int remainingCount;
    @Column(name = "done_count", nullable = false)
    private int doneCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Iteration getIteration() { return iteration; }
    public void setIteration(Iteration v) { iteration = v; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate v) { snapshotDate = v; }
    public BigDecimal getRemainingEstimate() { return remainingEstimate; }
    public void setRemainingEstimate(BigDecimal v) { remainingEstimate = v; }
    public int getRemainingCount() { return remainingCount; }
    public void setRemainingCount(int v) { remainingCount = v; }
    public int getDoneCount() { return doneCount; }
    public void setDoneCount(int v) { doneCount = v; }
    public Instant getCreatedAt() { return createdAt; }
}
