package com.iterationboard.admin;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.TeamMemberRepo;
import com.iterationboard.repo.TeamRepo;
import com.iterationboard.repo.UserRepo;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 平台管理只读接口（1.0.4，kubhs76k 管理员身份提前落地）：账号与小组总览。
 * 权限口径与 AuditLogController.adminAuditLogs 一致——仅平台管理员，非管理员 403。只读，无写接口。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final UserRepo userRepo;
    private final TeamRepo teamRepo;
    private final TeamMemberRepo memberRepo;
    private final CurrentUser currentUser;

    public AdminController(UserRepo userRepo, TeamRepo teamRepo, TeamMemberRepo memberRepo, CurrentUser currentUser) {
        this.userRepo = userRepo;
        this.teamRepo = teamRepo;
        this.memberRepo = memberRepo;
        this.currentUser = currentUser;
    }

    public record AdminUserDto(Long id, String loginName, String displayName, boolean platformAdmin, String status, Instant createdAt,
                               boolean mustChangePassword, boolean locked, Instant lastLoginAt) {
        /** rc3（契约 2）：locked = lockedUntil 在未来；供 AdminOpsController 的 unlock/force-logout 复用同一形状。 */
        public static AdminUserDto of(AppUser u) {
            return new AdminUserDto(u.getId(), u.getLoginName(), u.getDisplayName(),
                    u.isPlatformAdmin(), u.getStatus(), u.getCreatedAt(),
                    u.isMustChangePassword(),
                    u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now()),
                    u.getLastLoginAt());
        }
    }
    public record AdminTeamDto(Long id, String name, String code, long memberCount, Instant createdAt) { }

    private void requirePlatformAdmin() {
        AppUser me = currentUser.require();
        if (!me.isPlatformAdmin()) throw ApiException.forbidden("仅平台管理员可访问平台管理");
    }

    @Transactional(readOnly = true)
    @GetMapping("/users")
    public List<AdminUserDto> users() {
        requirePlatformAdmin();
        return userRepo.findAll().stream().map(AdminUserDto::of).toList();
    }

    @Transactional(readOnly = true)
    @GetMapping("/teams")
    public List<AdminTeamDto> teams() {
        requirePlatformAdmin();
        return teamRepo.findAll().stream()
                .map(t -> new AdminTeamDto(t.getId(), t.getName(), t.getCode(),
                        memberRepo.findByTeamId(t.getId()).size(), t.getCreatedAt()))
                .toList();
    }
}
