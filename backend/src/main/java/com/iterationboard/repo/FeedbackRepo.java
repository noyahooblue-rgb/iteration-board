package com.iterationboard.repo;

import com.iterationboard.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface FeedbackRepo extends JpaRepository<Feedback, Long> {
    List<Feedback> findAllByOrderByCreatedAtDesc();
    long countBySubmitterIdAndCreatedAtGreaterThanEqual(Long submitterId, Instant since);
}
