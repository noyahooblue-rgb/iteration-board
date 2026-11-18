package com.iterationboard.repo;

import com.iterationboard.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRepo extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByUserIdAndScopeAndKey(Long userId, String scope, String key);
    void deleteByUserIdAndScopeAndKey(Long userId, String scope, String key);
}
