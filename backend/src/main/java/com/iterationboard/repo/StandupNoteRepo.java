package com.iterationboard.repo;

import com.iterationboard.domain.StandupNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface StandupNoteRepo extends JpaRepository<StandupNote, Long> {
    Optional<StandupNote> findByTeamIdAndNoteDate(Long teamId, LocalDate date);
}
