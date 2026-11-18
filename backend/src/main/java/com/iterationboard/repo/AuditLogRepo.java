package com.iterationboard.repo;

import com.iterationboard.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepo extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
    List<AuditLog> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /** M5 留存清理：删除 createdAt 早于界限的审计日志，返回删除行数。 */
    long deleteByCreatedAtBefore(Instant cutoff);
}
