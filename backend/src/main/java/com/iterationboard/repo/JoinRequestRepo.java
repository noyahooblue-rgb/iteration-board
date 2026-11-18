package com.iterationboard.repo;

import com.iterationboard.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface JoinRequestRepo extends JpaRepository<JoinRequest, Long> {

        List<JoinRequest> findByTeamIdAndStatusOrderByCreatedAtAsc(Long teamId, String status);
        Optional<JoinRequest> findByTeamIdAndUserIdAndStatus(Long teamId, Long userId, String status);
        Optional<JoinRequest> findByIdAndTeamId(Long id, Long teamId);
}
