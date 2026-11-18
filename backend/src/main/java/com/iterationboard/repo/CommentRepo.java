package com.iterationboard.repo;

import com.iterationboard.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface CommentRepo extends JpaRepository<Comment, Long> {

        List<Comment> findByItemIdOrderByCreatedAtAsc(Long itemId);
        java.util.Optional<Comment> findByIdAndItemId(Long id, Long itemId);
        long countByItemId(Long itemId);
        List<Comment> findByContentContainingIgnoreCase(String keyword);
}
