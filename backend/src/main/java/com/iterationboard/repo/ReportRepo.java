package com.iterationboard.repo;

import com.iterationboard.domain.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReportRepo extends JpaRepository<Report, Long> {
    List<Report> findByTeamIdOrderByCreatedAtDesc(Long teamId);
    /** 周期唯一性查找（含已软删墓碑行）。 */
    Optional<Report> findFirstByTeamIdAndKindAndPeriodStartAndPeriodEndOrderByIdAsc(
            Long teamId, String kind, LocalDate periodStart, LocalDate periodEnd);
    Optional<Report> findFirstByTeamIdAndKindAndOwnerIdAndPeriodStartAndPeriodEndOrderByIdAsc(
            Long teamId, String kind, Long ownerId, LocalDate periodStart, LocalDate periodEnd);
    Optional<Report> findFirstByTeamIdAndKindAndIterationIdOrderByIdAsc(Long teamId, String kind, Long iterationId);
}
