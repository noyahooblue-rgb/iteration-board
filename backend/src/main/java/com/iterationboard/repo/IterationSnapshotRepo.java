package com.iterationboard.repo;

import com.iterationboard.domain.IterationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IterationSnapshotRepo extends JpaRepository<IterationSnapshot, Long> {
    List<IterationSnapshot> findByIterationIdOrderBySnapshotDateAsc(Long iterationId);
    Optional<IterationSnapshot> findByIterationIdAndSnapshotDate(Long iterationId, LocalDate date);
}
