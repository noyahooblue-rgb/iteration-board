package com.iterationboard.repo;

import com.iterationboard.domain.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedFilterRepo extends JpaRepository<SavedFilter, Long> {
    List<SavedFilter> findByTeamIdAndUserIdOrderByCreatedAtAsc(Long teamId, Long userId);
    Optional<SavedFilter> findByIdAndTeamIdAndUserId(Long id, Long teamId, Long userId);
}
