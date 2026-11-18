package com.iterationboard.team;

import com.iterationboard.common.ApiException;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.Role;
import com.iterationboard.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

@Service
public class TeamService {
    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final TeamRepo teamRepo;
    private final TeamMemberRepo memberRepo;
    private final JoinRequestRepo joinRequestRepo;
    private final UserRepo userRepo;
    private final com.iterationboard.common.AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public TeamService(TeamRepo teamRepo, TeamMemberRepo memberRepo,
                       JoinRequestRepo joinRequestRepo, UserRepo userRepo,
                       com.iterationboard.common.AuditService auditService) {
        this.teamRepo = teamRepo;
        this.memberRepo = memberRepo;
        this.joinRequestRepo = joinRequestRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
    }

    /** 成员身份校验：非成员一律 404，不泄露小组存在性。 */
    public TeamMember requireMember(Long teamId, AppUser user) {
        return memberRepo.findByTeamIdAndUserId(teamId, user.getId())
                .orElseThrow(() -> ApiException.notFound("小组不存在或无权访问"));
    }

    public TeamMember requireLeader(Long teamId, AppUser user) {
        TeamMember m = requireMember(teamId, user);
        if (m.getRole() != Role.LEADER) throw ApiException.forbidden("仅组长可执行此操作");
        return m;
    }

    public Team requireTeam(Long teamId) {
        return teamRepo.findById(teamId).orElseThrow(() -> ApiException.notFound("小组不存在"));
    }

    private String generateInviteCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(INVITE_CHARS.charAt(random.nextInt(INVITE_CHARS.length())));
            String code = sb.toString();
            if (teamRepo.findByInviteCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("邀请码生成失败");
    }

    @Transactional
    public Team createTeam(AppUser creator, String name, String code, String themeColor,
                           Integer iterationWeeks, Boolean joinApproval) {
        if (name == null || name.isBlank() || name.length() > 64) throw ApiException.validation("小组名称不能为空且不超过 64 字");
        if (code == null || !code.matches("[A-Za-z]{2,5}")) throw ApiException.validation("小组代号须为 2 至 5 个字母");
        // 代号全平台唯一（不区分大小写）：事项编号「代号-序号」在搜索/AI 问答中不能有歧义（验收意见三-4）。
        // 数据库另有 lower(code) 唯一索引（Flyway V2）兜住并发竞态，竞态时由全局异常处理映射为同一 409。
        if (teamRepo.existsByCodeIgnoreCase(code)) {
            throw new ApiException(HttpStatus.CONFLICT, "TEAM_CODE_TAKEN", "小组代号已被占用（不区分大小写），请换一个代号");
        }
        Team t = new Team();
        t.setName(name);
        t.setCode(code.toUpperCase());
        if (themeColor != null) t.setThemeColor(themeColor);
        if (iterationWeeks != null) {
            if (iterationWeeks < 1 || iterationWeeks > 4) throw ApiException.validation("迭代周期须为 1 至 4 周");
            t.setIterationWeeks(iterationWeeks);
        }
        if (joinApproval != null) t.setJoinApproval(joinApproval);
        t.setInviteCode(generateInviteCode());
        teamRepo.save(t);
        TeamMember m = new TeamMember();
        m.setTeam(t);
        m.setUser(creator);
        m.setRole(Role.LEADER);
        memberRepo.save(m);
        return t;
    }

    @Transactional(readOnly = true)
    public List<TeamMember> myMemberships(AppUser user) {
        return memberRepo.findByUserId(user.getId());
    }

    /** 事务内映射：TeamMember.team 为 LAZY 代理，禁止在控制器层（事务外）读其字段（修订二场景测试发现的 500 修复，同 JoinRequest 口径）。 */
    @Transactional(readOnly = true)
    public List<com.iterationboard.config.Dtos.TeamDto> myTeamDtos(AppUser user) {
        return myMemberships(user).stream()
                .map(m -> com.iterationboard.config.Dtos.TeamDto.of(m.getTeam(), m.getRole(), m.getRole() == Role.LEADER))
                .toList();
    }

    /** 事务内映射：TeamMember.user 为 LAZY 代理，同上。 */
    @Transactional(readOnly = true)
    public List<com.iterationboard.config.Dtos.MemberDto> memberDtos(Long teamId, AppUser user) {
        return members(teamId, user).stream().map(com.iterationboard.config.Dtos.MemberDto::of).toList();
    }

    @Transactional(readOnly = true)
    public Team findByInviteCode(String inviteCode) {
        return teamRepo.findByInviteCode(inviteCode)
                .orElseThrow(() -> ApiException.notFound("邀请码无效"));
    }

    @Transactional(readOnly = true)
    public String leaderName(Team team) {
        return memberRepo.findByTeamId(team.getId()).stream()
                .filter(m -> m.getRole() == Role.LEADER)
                .findFirst().map(m -> m.getUser().getDisplayName()).orElse("");
    }

    public record JoinOutcome(boolean joined, Team team, JoinRequest request) {}

    @Transactional
    public JoinOutcome join(AppUser user, String inviteCode) {
        Team team = findByInviteCode(inviteCode);
        if (memberRepo.findByTeamIdAndUserId(team.getId(), user.getId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_MEMBER", "已是该小组成员");
        }
        if (!team.isJoinApproval()) {
            TeamMember m = new TeamMember();
            m.setTeam(team);
            m.setUser(user);
            m.setRole(Role.MEMBER);
            memberRepo.save(m);
            return new JoinOutcome(true, team, null);
        }
        if (joinRequestRepo.findByTeamIdAndUserIdAndStatus(team.getId(), user.getId(), "PENDING").isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "JOIN_REQUEST_PENDING", "已有待审批的加入申请");
        }
        JoinRequest r = new JoinRequest();
        r.setTeam(team);
        r.setUser(user);
        joinRequestRepo.save(r);
        return new JoinOutcome(false, team, r);
    }

    @Transactional(readOnly = true)
    public List<JoinRequest> pendingRequests(Long teamId, AppUser user) {
        requireLeader(teamId, user);
        return joinRequestRepo.findByTeamIdAndStatusOrderByCreatedAtAsc(teamId, "PENDING");
    }

    /** 在事务内完成 DTO 映射：JoinRequest.user 为 LAZY 关联，禁止在控制器层（事务外）映射（修订二场景测试发现的 500 修复）。 */
    @Transactional(readOnly = true)
    public List<com.iterationboard.config.Dtos.JoinRequestDto> pendingRequestDtos(Long teamId, AppUser user) {
        return pendingRequests(teamId, user).stream().map(com.iterationboard.config.Dtos.JoinRequestDto::of).toList();
    }

    @Transactional
    public void approve(Long teamId, Long requestId, Role role, AppUser leader) {
        requireLeader(teamId, leader);
        JoinRequest r = joinRequestRepo.findByIdAndTeamId(requestId, teamId)
                .filter(x -> "PENDING".equals(x.getStatus()))
                .orElseThrow(() -> ApiException.notFound("申请不存在或已处理"));
        Team team = requireTeam(teamId);
        if (memberRepo.findByTeamIdAndUserId(teamId, r.getUser().getId()).isEmpty()) {
            TeamMember m = new TeamMember();
            m.setTeam(team);
            m.setUser(r.getUser());
            m.setRole(role == null ? Role.MEMBER : role);
            memberRepo.save(m);
        }
        r.setStatus("APPROVED");
        r.setApprover(leader);
        r.setDecidedAt(Instant.now());
        joinRequestRepo.save(r);
    }

    @Transactional
    public void reject(Long teamId, Long requestId, AppUser leader) {
        requireLeader(teamId, leader);
        JoinRequest r = joinRequestRepo.findByIdAndTeamId(requestId, teamId)
                .filter(x -> "PENDING".equals(x.getStatus()))
                .orElseThrow(() -> ApiException.notFound("申请不存在或已处理"));
        r.setStatus("REJECTED");
        r.setApprover(leader);
        r.setDecidedAt(Instant.now());
        joinRequestRepo.save(r);
    }

    @Transactional(readOnly = true)
    public List<TeamMember> members(Long teamId, AppUser user) {
        requireMember(teamId, user);
        return memberRepo.findByTeamId(teamId);
    }

    @Transactional
    public void assignRole(Long teamId, Long targetUserId, Role role, AppUser leader) {
        requireLeader(teamId, leader);
        if (role == null) throw ApiException.validation("角色不能为空");
        TeamMember target = memberRepo.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("该用户不是小组成员"));
        if (target.getRole() == Role.LEADER && role != Role.LEADER
                && memberRepo.countByTeamIdAndRole(teamId, Role.LEADER) <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LAST_LEADER", "小组至少保留一名组长，不能降级最后一名组长");
        }
        target.setRole(role);
        memberRepo.save(target);
        auditService.log(teamId, leader, "CHANGE_ROLE", "MEMBER", targetUserId,
                "成员角色调整为 " + role);
    }

    @Transactional
    public String resetInviteCode(Long teamId, AppUser leader) {
        requireLeader(teamId, leader);
        Team t = requireTeam(teamId);
        t.setInviteCode(generateInviteCode());
        teamRepo.save(t);
        auditService.log(teamId, leader, "RESET_INVITE_CODE", "TEAM", teamId, "重置邀请码");
        return t.getInviteCode();
    }

    // ---------- M3：小组设置 / 归档 / 退出 / 成员状态（契约缺口 a，契约 0.3.0） ----------

    public record TeamPatch(String name, String description, String themeColor, Integer iterationWeeks,
                            Boolean joinApproval, String estimateUnit, tools.jackson.databind.JsonNode settings) {}

    @Transactional
    public Team updateTeam(Long teamId, AppUser user, TeamPatch patch) {
        requireLeader(teamId, user);
        Team t = requireTeam(teamId);
        if (patch.name() != null) {
            if (patch.name().isBlank() || patch.name().length() > 64) throw ApiException.validation("小组名称不能为空且不超过 64 字");
            t.setName(patch.name());
        }
        if (patch.description() != null) t.setDescription(patch.description());
        if (patch.themeColor() != null) t.setThemeColor(patch.themeColor());
        if (patch.iterationWeeks() != null) {
            if (patch.iterationWeeks() < 1 || patch.iterationWeeks() > 4) throw ApiException.validation("迭代周期须为 1 至 4 周");
            t.setIterationWeeks(patch.iterationWeeks());
        }
        if (patch.joinApproval() != null) t.setJoinApproval(patch.joinApproval());
        if (patch.estimateUnit() != null) {
            if (!List.of("POINT", "HOUR", "DAY").contains(patch.estimateUnit())) throw ApiException.validation("估算单位须为 POINT / HOUR / DAY");
            t.setEstimateUnit(patch.estimateUnit());
        }
        if (patch.settings() != null) {
            t.setSettingsJson(TeamSettings.mergeInto(t.getSettingsJson(), patch.settings()));
        }
        teamRepo.save(t);
        auditService.log(teamId, user, "UPDATE_TEAM_SETTINGS", "TEAM", teamId, "修改小组设置");
        return t;
    }

    @Transactional
    public Team archive(Long teamId, AppUser user) {
        requireLeader(teamId, user);
        Team t = requireTeam(teamId);
        t.setStatus("ARCHIVED");
        teamRepo.save(t);
        return t;
    }

    @Transactional
    public void leave(Long teamId, AppUser user) {
        TeamMember m = requireMember(teamId, user);
        if (m.getRole() == Role.LEADER && memberRepo.countByTeamIdAndRole(teamId, Role.LEADER) <= 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LAST_LEADER", "你是小组唯一的组长，请先指定其他组长后再退出");
        }
        memberRepo.delete(m);
    }

    /** 事务内映射版 setMemberStatus：返回的 TeamMember.user 为 LAZY 代理，DTO 映射必须在事务内（修订二场景测试发现的 500 修复）。 */
    @Transactional
    public com.iterationboard.config.Dtos.MemberDto setMemberStatusDto(Long teamId, Long targetUserId, AppUser actor,
                                      String memberStatus, java.time.LocalDate start, java.time.LocalDate end) {
        return com.iterationboard.config.Dtos.MemberDto.of(setMemberStatus(teamId, targetUserId, actor, memberStatus, start, end));
    }

    private static final List<String> MEMBER_STATUSES = List.of("NORMAL", "LEAVE", "TRAVEL", "SECONDMENT");

    @Transactional
    public TeamMember setMemberStatus(Long teamId, Long targetUserId, AppUser actor,
                                      String memberStatus, java.time.LocalDate start, java.time.LocalDate end) {
        TeamMember actorMember = requireMember(teamId, actor);
        if (!actor.getId().equals(targetUserId) && actorMember.getRole() != Role.LEADER) {
            throw ApiException.forbidden("只能设置自己的状态，组长可代设他人状态");
        }
        if (memberStatus == null || !MEMBER_STATUSES.contains(memberStatus)) {
            throw ApiException.validation("memberStatus 须为 NORMAL / LEAVE / TRAVEL / SECONDMENT");
        }
        if (!"NORMAL".equals(memberStatus)) {
            if (start != null && end != null && end.isBefore(start)) throw ApiException.validation("结束日期不能早于开始日期");
        }
        TeamMember target = memberRepo.findByTeamIdAndUserId(teamId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("该用户不是小组成员"));
        target.setMemberStatus(memberStatus);
        if ("NORMAL".equals(memberStatus)) {
            target.setStatusStart(null);
            target.setStatusEnd(null);
        } else {
            target.setStatusStart(start);
            target.setStatusEnd(end);
        }
        memberRepo.save(target);
        auditService.log(teamId, actor, "UPDATE_MEMBER_STATUS", "MEMBER", targetUserId,
                "成员状态改为 " + memberStatus);
        return target;
    }
}
