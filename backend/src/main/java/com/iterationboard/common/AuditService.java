package com.iterationboard.common;

import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.AuditLog;
import com.iterationboard.repo.AuditLogRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 审计写入点（Q6：读取界面 M5，本轮只写）。随调用方事务一起提交。 */
@Service
public class AuditService {
    private final AuditLogRepo repo;

    public AuditService(AuditLogRepo repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void log(Long teamId, AppUser actor, String action, String targetType, Long targetId, String detail) {
        repo.save(new AuditLog(teamId, actor, action, targetType, targetId, detail));
    }
}
