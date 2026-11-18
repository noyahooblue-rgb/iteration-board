package com.iterationboard.repo;

import com.iterationboard.domain.ReportVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReportVersionRepo extends JpaRepository<ReportVersion, Long> {
    List<ReportVersion> findByReportIdOrderByCreatedAtDescIdDesc(Long reportId);
    Optional<ReportVersion> findByIdAndReportId(Long id, Long reportId);
    long countByReportId(Long reportId);
}
