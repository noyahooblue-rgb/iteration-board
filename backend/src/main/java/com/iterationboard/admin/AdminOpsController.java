package com.iterationboard.admin;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.AuditService;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.TeamMemberRepo;
import com.iterationboard.repo.UserRepo;
import com.iterationboard.team.HandoverService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台管理接口（1.1.0）：授予/撤销平台管理员、停用/启用/删除账号、备份状态。
 * 全部接口要求平台管理员会话（SecurityConfig 对 /api/v1/admin/** 按库中标记授权，
 * 本类再以 requirePlatformAdmin 二次校验）。
 * 保护规则：最后一名「启用中」的平台管理员不可被撤销、停用或删除；admin 账号不可删除；
 * 停用账号前必须完成其在每个所在小组的离组交接（P05 强校验）。
 *
 * 1.1.0-rc2 安全裁定（hsa43amn）：原免登录的 POST /bootstrap、GET /bootstrap/status、
 * POST /reset-admin 三接口已删除——它们是「脚本调 HTTP」旧方案的遗留，局域网内任何人
 * 可借此改密/自建管理员接管平台。建号改由 AdminBootstrapRunner 在应用内完成，
 * 应急重置改走 `java -jar app.jar --ib.ops=reset-admin` 运维命令（ResetAdminOpsRunner）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOpsController {
    private final UserRepo userRepo;
    private final TeamMemberRepo memberRepo;
    private final HandoverService handoverService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final BackupStatusService backupStatusService;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final com.iterationboard.item.NotifyService notifyService;

    public AdminOpsController(UserRepo userRepo,
                              TeamMemberRepo memberRepo, HandoverService handoverService,
                              AuditService auditService, CurrentUser currentUser,
                              BackupStatusService backupStatusService,
                              org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                              com.iterationboard.item.NotifyService notifyService) {
        this.userRepo = userRepo;
        this.memberRepo = memberRepo;
        this.handoverService = handoverService;
        this.auditService = auditService;
        this.currentUser = currentUser;
        this.backupStatusService = backupStatusService;
        this.passwordEncoder = passwordEncoder;
        this.notifyService = notifyService;
    }

    public record PlatformAdminRequest(boolean platformAdmin) {}
    public record StatusRequest(String status) {}
    public record ResetPasswordRequest(String adminPassword) {}

    // ---------- 管理员会话操作 ----------

    private AppUser requirePlatformAdmin() {
        AppUser me = currentUser.require();
        if (!me.isPlatformAdmin()) throw ApiException.forbidden("仅平台管理员可执行此操作");
        return me;
    }

    private long enabledAdminCount() {
        return userRepo.findAll().stream()
                .filter(u -> u.isPlatformAdmin() && "ACTIVE".equals(u.getStatus()) && u.getDeletedAt() == null)
                .count();
    }

    private void assertNotLastEnabledAdmin(AppUser target, String action) {
        if (target.isPlatformAdmin() && "ACTIVE".equals(target.getStatus()) && target.getDeletedAt() == null
                && enabledAdminCount() <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LAST_PLATFORM_ADMIN",
                    "系统至少保留一名启用中的平台管理员，不能" + action + "最后一名管理员");
        }
    }

    @PatchMapping("/users/{userId}/platform-admin")
    @Transactional
    public Dtos.UserDto setPlatformAdmin(@PathVariable Long userId, @RequestBody PlatformAdminRequest req) {
        AppUser me = requirePlatformAdmin();
        AppUser target = userRepo.findById(userId).orElseThrow(() -> ApiException.notFound("账号不存在"));
        if (!req.platformAdmin()) assertNotLastEnabledAdmin(target, "撤销");
        target.setPlatformAdmin(req.platformAdmin());
        userRepo.save(target);
        auditService.log(null, me, req.platformAdmin() ? "GRANT_PLATFORM_ADMIN" : "REVOKE_PLATFORM_ADMIN",
                "USER", userId, (req.platformAdmin() ? "授予" : "撤销") + "平台管理员：" + target.getLoginName());
        return Dtos.UserDto.of(target);
    }

    @PatchMapping("/users/{userId}/status")
    @Transactional
    public Dtos.UserDto setStatus(@PathVariable Long userId, @RequestBody StatusRequest req) {
        AppUser me = requirePlatformAdmin();
        if (!"ACTIVE".equals(req.status()) && !"DISABLED".equals(req.status())) {
            throw ApiException.validation("status 须为 ACTIVE / DISABLED");
        }
        AppUser target = userRepo.findById(userId).orElseThrow(() -> ApiException.notFound("账号不存在"));
        if ("DISABLED".equals(req.status())) {
            assertNotLastEnabledAdmin(target, "停用");
            // P05：停用前对每个所在小组做交接校验，并自动摘除共同负责
            for (var membership : memberRepo.findByUserId(userId)) {
                handoverService.assertCleared(membership.getTeam().getId(), userId);
            }
            for (var membership : memberRepo.findByUserId(userId)) {
                handoverService.removeCollaborations(membership.getTeam().getId(), userId);
            }
        }
        target.setStatus(req.status());
        if ("DISABLED".equals(req.status())) {
            target.setPasswordVersion(target.getPasswordVersion() + 1);
        }
        userRepo.save(target);
        auditService.log(null, me, "DISABLED".equals(req.status()) ? "DISABLE_USER" : "ENABLE_USER",
                "USER", userId, "账号状态改为 " + req.status() + "：" + target.getLoginName());
        return Dtos.UserDto.of(target);
    }

    @DeleteMapping("/users/{userId}")
    @Transactional
    public ResponseEntity<Void> deleteUser(@PathVariable Long userId) {
        AppUser me = requirePlatformAdmin();
        AppUser target = userRepo.findById(userId).orElseThrow(() -> ApiException.notFound("账号不存在"));
        if (AdminBootstrapService.DEFAULT_ADMIN_LOGIN.equals(target.getLoginName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ADMIN_NOT_DELETABLE", "admin 账号不能被删除（可停用）");
        }
        assertNotLastEnabledAdmin(target, "删除");
        target.setDeletedAt(java.time.Instant.now());
        target.setStatus("DISABLED");
        target.setPasswordVersion(target.getPasswordVersion() + 1);
        userRepo.save(target);
        auditService.log(null, me, "DELETE_USER", "USER", userId, "删除账号：" + target.getLoginName());
        return ResponseEntity.noContent().build();
    }

    // ---------- rc3（修订六契约 2）：重置密码 / 解除锁定 / 强制下线 ----------

    /**
     * 重置他人密码：二次确认调用者本人密码后，生成 16 位合规临时密码（只在本次响应返回一次）；
     * 目标强制改密、passwordVersion+1（旧会话失效）、解除锁定；审计不记密码；给目标发 PASSWORD_RESET 通知。
     * 不能重置自己。仅平台管理员（组长亦 403）。
     */
    @PostMapping("/users/{userId}/reset-password")
    @Transactional
    public Map<String, Object> resetPassword(@PathVariable Long userId, @RequestBody ResetPasswordRequest req) {
        AppUser me = requirePlatformAdmin();
        if (req.adminPassword() == null || !passwordEncoder.matches(req.adminPassword(), me.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_PASSWORD", "管理员密码不正确");
        }
        AppUser target = userRepo.findById(userId).orElseThrow(() -> ApiException.notFound("账号不存在"));
        if (target.getId().equals(me.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_RESET_SELF", "不能重置自己的密码，请到个人设置修改");
        }
        String temp = AdminPasswordSupport.generatePassword(16);
        target.setPasswordHash(passwordEncoder.encode(temp));
        target.setMustChangePassword(true);
        target.setPasswordVersion(target.getPasswordVersion() + 1);
        target.setFailedAttempts(0);
        target.setLockedUntil(null);
        userRepo.save(target);
        auditService.log(null, me, "RESET_USER_PASSWORD", "USER", userId, "重置密码：" + target.getLoginName());
        notifyService.notifyInTeam(target, "PASSWORD_RESET", null, null, me,
                "你的密码已由管理员重置，请使用管理员告知的临时密码登录并修改", false, null);
        return Map.of("temporaryPassword", temp);
    }

    /** 解除锁定：清 lockedUntil 与失败次数，返回更新后的用户行。 */
    @PostMapping("/users/{userId}/unlock")
    @Transactional
    public AdminController.AdminUserDto unlock(@PathVariable Long userId) {
        AppUser me = requirePlatformAdmin();
        AppUser target = userRepo.findById(userId).orElseThrow(() -> ApiException.notFound("账号不存在"));
        target.setLockedUntil(null);
        target.setFailedAttempts(0);
        userRepo.save(target);
        auditService.log(null, me, "UNLOCK_USER", "USER", userId, "解除锁定：" + target.getLoginName());
        return AdminController.AdminUserDto.of(target);
    }

    /** 强制下线：passwordVersion+1 使其全部会话失效，不改密码，返回更新后的用户行。 */
    @PostMapping("/users/{userId}/force-logout")
    @Transactional
    public AdminController.AdminUserDto forceLogout(@PathVariable Long userId) {
        AppUser me = requirePlatformAdmin();
        AppUser target = userRepo.findById(userId).orElseThrow(() -> ApiException.notFound("账号不存在"));
        target.setPasswordVersion(target.getPasswordVersion() + 1);
        userRepo.save(target);
        auditService.log(null, me, "FORCE_LOGOUT", "USER", userId, "强制下线：" + target.getLoginName());
        return AdminController.AdminUserDto.of(target);
    }

    /** P23：备份状态（管理员顶部「超 3 天未备份」提示与系统信息共用）。 */
    @GetMapping("/backup-status")
    public Map<String, Object> backupStatus() {
        requirePlatformAdmin();
        return backupStatusService.status();
    }
}
