package com.iterationboard.repo;

import com.iterationboard.domain.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiCallLogRepo extends JpaRepository<AiCallLog, Long> {
    List<AiCallLog> findByTeamIdOrderByCreatedAtDesc(Long teamId);
}
