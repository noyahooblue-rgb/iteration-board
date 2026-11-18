package com.iterationboard.repo;

import com.iterationboard.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ImportBatchRepo extends JpaRepository<ImportBatch, Long> {
    List<ImportBatch> findByTeamIdOrderByCreatedAtDesc(Long teamId);
}
