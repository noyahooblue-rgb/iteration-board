package com.iterationboard.repo;

import com.iterationboard.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface FeedbackRepo extends JpaRepository<Feedback, Long> {
    List<Feedback> findAllByOrderByCreatedAtDesc();
    List<Feedback> findBySubmitterIdOrderByCreatedAtDesc(Long submitterId);
    long countBySubmitterIdAndCreatedAtGreaterThanEqual(Long submitterId, Instant since);
}
