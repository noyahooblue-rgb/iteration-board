package com.iterationboard.admin;

import com.iterationboard.common.AuditService;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.UserRepo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 管理员启动初始化的事务部分（与 {@link AdminBootstrapRunner} 分工：本服务只管数据库，
 * 密码文件由 runner 在事务提交后写，避免事务回滚后留下指向不存在账号的密码文件）。
 */
@Service
public class AdminBootstrapTxService {

    public enum Outcome {
        /** 本次新建了 admin，generatedPassword 为其初始密码 */
        CREATED,
        /** admin 已存在且是平台管理员，无事可做 */
        ALREADY_ADMIN,
        /** login_name='admin' 已被普通账号占用，不改动它（审计 ADMIN_BOOTSTRAP_SKIPPED 已落行） */
        SKIPPED_NAME_TAKEN
    }

    public record Result(Outcome outcome, String generatedPassword) {}

    /**
     * 并发防重锁键：任意固定的 64 位常量即可（取 "IB" 的 ASCII 0x4942 左移后拼年份风格数字，
     * 只需全应用一致、无业务含义）。用 pg_advisory_xact_lock，随事务结束自动释放。
     */
    private static final long ADVISORY_LOCK_KEY = 4_942_011_001L;

    private final UserRepo userRepo;
    private final PasswordEncoder encoder;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    public AdminBootstrapTxService(UserRepo userRepo, PasswordEncoder encoder,
                                   AuditService auditService, JdbcTemplate jdbc) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    @Transactional
    public Result bootstrapAdmin() {
        // 事务级建议锁：多实例并发启动时串行化「查 admin → 建 admin」，防重复创建
        jdbc.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");

        Optional<AppUser> existing = userRepo.findByLoginName(AdminBootstrapService.DEFAULT_ADMIN_LOGIN);
        if (existing.isPresent()) {
            AppUser u = existing.get();
            if (u.isPlatformAdmin()) {
                return new Result(Outcome.ALREADY_ADMIN, null);
            }
            // 用户自注册抢占了 admin 登录名：不改动该账号，只留痕，提示走 reset-admin 运维命令处理
            auditService.log(null, u, "ADMIN_BOOTSTRAP_SKIPPED", "USER", u.getId(),
                    "启动初始化跳过：登录名 admin 已被普通账号占用，未做任何改动；如需设置平台管理员请使用 reset-admin 运维命令");
            return new Result(Outcome.SKIPPED_NAME_TAKEN, null);
        }

        String password = AdminPasswordSupport.generatePassword();
        AppUser u = new AppUser();
        u.setLoginName(AdminBootstrapService.DEFAULT_ADMIN_LOGIN);
        u.setDisplayName("平台管理员");
        u.setPasswordHash(encoder.encode(password));
        u.setAvatarColor("indigo");
        u.setPlatformAdmin(true);
        u.setStatus("ACTIVE");
        u.setMustChangePassword(true);
        userRepo.save(u);
        auditService.log(null, u, "ADMIN_BOOTSTRAP", "USER", u.getId(),
                "应用启动初始化创建平台管理员账号 admin（初始密码已写入密码文件，首次登录须改密）");
        return new Result(Outcome.CREATED, password);
    }
}
