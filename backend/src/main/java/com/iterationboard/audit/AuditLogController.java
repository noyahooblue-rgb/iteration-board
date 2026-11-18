package com.iterationboard.audit;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.AuditLog;
import com.iterationboard.domain.Enums.Role;
import com.iterationboard.repo.AuditLogRepo;
import com.iterationboard.repo.TeamMemberRepo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计日志读取侧（Q6，M5 落地；写入侧见 common/AuditService）。只读，无写接口。
 * 权限：小组接口——本组组长（LEADER）或平台管理员可读；普通成员/PO 403；
 * 非成员 404（契约 0.5.3，M5 验收结论 2：与全仓「不存在或无权访问」返回 404 的约定一致，不泄露小组是否存在）。
 * 管理接口——仅平台管理员，非管理员 403。
 */
@RestController
@RequestMapping("/api/v1")
public class AuditLogController {
    private final AuditLogRepo auditLogRepo;
    private final TeamMemberRepo memberRepo;
    private final CurrentUser currentUser;

    public AuditLogController(AuditLogRepo auditLogRepo, TeamMemberRepo memberRepo, CurrentUser currentUser) {
        this.auditLogRepo = auditLogRepo;
        this.memberRepo = memberRepo;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    @GetMapping("/teams/{teamId}/audit-logs")
    public Map<String, Object> teamAuditLogs(@PathVariable Long teamId,
                                             @RequestParam(required = false) Long actorId,
                                             @RequestParam(required = false) String action,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        AppUser me = currentUser.require();
        if (!me.isPlatformAdmin()) {
            var member = memberRepo.findByTeamIdAndUserId(teamId, me.getId());
            if (member.isEmpty()) {
                // 结论 2（0.5.3）：非成员返回 404，与全仓约定一致，不泄露小组是否存在
                throw ApiException.notFound("小组不存在或无权访问");
            }
            if (member.get().getRole() != Role.LEADER) {
                throw ApiException.forbidden("仅本组组长或平台管理员可查看审计日志");
            }
        }
        return pageResult(auditLogRepo.findAll(spec(teamId, actorId, action, from, to), pageable(page, size)));
    }

    @Transactional(readOnly = true)
    @GetMapping("/admin/audit-logs")
    public Map<String, Object> adminAuditLogs(@RequestParam(required = false) Long teamId,
                                              @RequestParam(required = false) Long actorId,
                                              @RequestParam(required = false) String action,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        AppUser me = currentUser.require();
        if (!me.isPlatformAdmin()) throw ApiException.forbidden("仅平台管理员可查看全部审计日志");
        return pageResult(auditLogRepo.findAll(spec(teamId, actorId, action, from, to), pageable(page, size)));
    }

    /**
     * 筛选条件动态拼接（只拼传入的条件）：不用「参数 is null or 列=参数」的 JPQL 写法——
     * PostgreSQL 无法推断该写法中 Instant 参数的类型（实测 500 could not determine data type）。
     */
    private Specification<AuditLog> spec(Long teamId, Long actorId, String action, Instant from, Instant to) {
        Specification<AuditLog> spec = (root, q, cb) -> cb.conjunction();
        if (teamId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("teamId"), teamId));
        if (actorId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("actor").get("id"), actorId));
        if (action != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action));
        if (from != null) spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        if (to != null) spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        return spec;
    }

    private PageRequest pageable(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        // 默认按时间倒序（同刻以 id 倒序稳定排序）
        return PageRequest.of(p, s, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private Map<String, Object> pageResult(Page<AuditLog> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", result.getContent().stream().map(AuditLogController::toMap).toList());
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        return out;
    }

    static Map<String, Object> toMap(AuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("teamId", a.getTeamId());
        AppUser actor = a.getActor();
        m.put("actorId", actor == null ? null : actor.getId());
        m.put("actorName", actor == null ? null : actor.getDisplayName());
        m.put("action", a.getAction());
        m.put("targetType", a.getTargetType());
        m.put("targetId", a.getTargetId());
        m.put("detail", a.getDetail());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }
}
