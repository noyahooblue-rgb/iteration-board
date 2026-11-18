package com.iterationboard.repo;

import com.iterationboard.domain.DispatchBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DispatchBatchRepo extends JpaRepository<DispatchBatch, Long> {
    Optional<DispatchBatch> findByIdAndTeamId(Long id, Long teamId);
}
