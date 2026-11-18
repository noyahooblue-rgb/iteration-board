package com.iterationboard.repo;

import com.iterationboard.domain.IntegrationAdapter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IntegrationAdapterRepo extends JpaRepository<IntegrationAdapter, Long> {
    List<IntegrationAdapter> findByTeamId(Long teamId);
}
