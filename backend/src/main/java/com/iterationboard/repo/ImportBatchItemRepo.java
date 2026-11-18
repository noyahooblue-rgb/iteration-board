package com.iterationboard.repo;

import com.iterationboard.domain.ImportBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ImportBatchItemRepo extends JpaRepository<ImportBatchItem, Long> {
    List<ImportBatchItem> findByBatchId(Long batchId);
}
