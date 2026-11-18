package com.iterationboard.repo;

import com.iterationboard.domain.Nudge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NudgeRepo extends JpaRepository<Nudge, Long> {
    boolean existsByItemIdAndNudgerIdAndCreatedAtGreaterThanEqual(Long itemId, Long nudgerId, Instant since);
    List<Nudge> findByItemId(Long itemId);
    /** 被催人对某事项最近一条未回应的催（快捷回应用）。 */
    Optional<Nudge> findFirstByItemIdAndTargetIdAndResponseIsNullOrderByCreatedAtDesc(Long itemId, Long targetId);
    /** Q13 幂等安全：已回应过的最近一条（重复回应时回放当前状态）。 */
    Optional<Nudge> findFirstByItemIdAndTargetIdAndResponseIsNotNullOrderByRespondedAtDesc(Long itemId, Long targetId);
    long countByItemId(Long itemId);
}
