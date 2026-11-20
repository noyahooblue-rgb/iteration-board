package com.iterationboard.admin;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.AuditService;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.UserRepo;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * reset-admin 运维命令的事务部分：在 Java 内完成账号重置（替代旧 bat 里拼 SQL 的做法）。
 * 返回生成的新密码供 runner 在事务提交后写密码文件；账号不存在直接抛错，绝不自动创建普通账号。
 */
@Service
public class ResetAdminOpsService {

    private final UserRepo userRepo;
    private final PasswordEncoder encoder;
    private final AuditService auditService;

    public ResetAdminOpsService(UserRepo userRepo, PasswordEncoder encoder, AuditService auditService) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.auditService = auditService;
    }

    @Transactional
    public String resetAdmin(String loginName) {
        String login = loginName == null ? "" : loginName.trim();
        AppUser u = userRepo.findByLoginName(login)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                        "账号不存在：" + login));
        String password = AdminPasswordSupport.generatePassword();
        u.setPasswordHash(encoder.encode(password));
        u.setPlatformAdmin(true);
        u.setStatus("ACTIVE");
        u.setDeletedAt(null);
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setMustChangePassword(true);
        u.setPasswordVersion(u.getPasswordVersion() + 1);
        userRepo.save(u);
        auditService.log(null, u, "ADMIN_RESET_VIA_OPS", "USER", u.getId(),
                "reset-admin 运维命令重置：设为平台管理员并生成新初始密码（已写入密码文件，首次登录须改密）");
        return password;
    }
}
