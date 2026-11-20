package com.iterationboard.repo;

import com.iterationboard.domain.FeedbackPlusOne;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedbackPlusOneRepo extends JpaRepository<FeedbackPlusOne, Long> {
    List<FeedbackPlusOne> findByFeedbackId(Long feedbackId);
    Optional<FeedbackPlusOne> findByFeedbackIdAndUserId(Long feedbackId, Long userId);
    long countByFeedbackId(Long feedbackId);
    void deleteByFeedbackIdAndUserId(Long feedbackId, Long userId);
}
