package com.iterationboard.repo;

import com.iterationboard.domain.FeedbackImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackImageRepo extends JpaRepository<FeedbackImage, Long> {
    List<FeedbackImage> findByFeedbackId(Long feedbackId);
    void deleteByFeedbackId(Long feedbackId);
}
