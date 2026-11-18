package com.iterationboard.repo;

import com.iterationboard.domain.AiQaRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiQaRecordRepo extends JpaRepository<AiQaRecord, Long> {
    List<AiQaRecord> findByTeamIdAndUserIdOrderByCreatedAtDesc(Long teamId, Long userId);
}
