package com.iterationboard.repo;

import com.iterationboard.domain.FeedbackComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackCommentRepo extends JpaRepository<FeedbackComment, Long> {
    List<FeedbackComment> findByFeedbackIdOrderByCreatedAtAsc(Long feedbackId);
    long countByFeedbackId(Long feedbackId);
}
