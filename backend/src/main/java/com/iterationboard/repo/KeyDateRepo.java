package com.iterationboard.repo;

import com.iterationboard.domain.KeyDate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KeyDateRepo extends JpaRepository<KeyDate, Long> {
    List<KeyDate> findByIterationIdOrderByDateAsc(Long iterationId);
}
