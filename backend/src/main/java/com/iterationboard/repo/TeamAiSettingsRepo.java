package com.iterationboard.repo;

import com.iterationboard.domain.TeamAiSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TeamAiSettingsRepo extends JpaRepository<TeamAiSettings, Long> {
}
