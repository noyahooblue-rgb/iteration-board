package com.iterationboard.repo;

import com.iterationboard.domain.SyncLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SyncLogRepo extends JpaRepository<SyncLog, Long> {
    List<SyncLog> findByAdapterIdOrderByCreatedAtDesc(Long adapterId);
    List<SyncLog> findByTeamIdOrderByCreatedAtDesc(Long teamId);
}
