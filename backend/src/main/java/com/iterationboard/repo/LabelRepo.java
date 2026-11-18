package com.iterationboard.repo;

import com.iterationboard.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface LabelRepo extends JpaRepository<Label, Long> {

        Optional<Label> findByTeamIdAndName(Long teamId, String name);
        List<Label> findByTeamId(Long teamId);
}
