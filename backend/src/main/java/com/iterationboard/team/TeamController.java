package com.iterationboard.team;

import com.iterationboard.config.CurrentUser;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Enums;
import com.iterationboard.domain.Team;
import com.iterationboard.domain.TeamMember;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {
    private final TeamService teamService;
    private final HandoverService handoverService;
    private final CurrentUser currentUser;

    public TeamController(TeamService teamService, HandoverService handoverService, CurrentUser currentUser) {
        this.teamService = teamService;
        this.handoverService = handoverService;
        this.currentUser = currentUser;
    }

    public record CreateTeamRequest(String name, String code, String themeColor, Integer iterationWeeks, Boolean joinApproval) {}
    public record JoinRequestBody(String inviteCode) {}
    public record ApproveRequest(Enums.Role role) {}
    public record RoleRequest(Enums.Role role) {}

    @PostMapping
    public ResponseEntity<Dtos.TeamDto> create(@RequestBody CreateTeamRequest req) {
        AppUser u = currentUser.require();
        Team t = teamService.createTeam(u, req.name(), req.code(), req.themeColor(), req.iterationWeeks(), req.joinApproval());
        return ResponseEntity.status(HttpStatus.CREATED).body(Dtos.TeamDto.of(t, Enums.Role.LEADER, true));
    }

    @GetMapping("/mine")
    public List<Dtos.TeamDto> mine() {
        AppUser u = currentUser.require();
        return teamService.myTeamDtos(u);
    }

    @GetMapping("/by-invite/{inviteCode}")
    public Map<String, Object> preview(@PathVariable String inviteCode) {
        AppUser u = currentUser.require();
        Team t = teamService.findByInviteCode(inviteCode);
        boolean already = teamService.myMemberships(u).stream().anyMatch(m -> m.getTeam().getId().equals(t.getId()));
        return Map.of(
                "team", Dtos.TeamDto.of(t, null, false),
                "leaderName", teamService.leaderName(t),
                "alreadyMember", already);
    }

    @PostMapping("/join")
    public Map<String, Object> join(@RequestBody JoinRequestBody req) {
        AppUser u = currentUser.require();
        TeamService.JoinOutcome out = teamService.join(u, req.inviteCode());
        return Map.of(
                "joined", out.joined(),
                "team", Dtos.TeamDto.of(out.team(), out.joined() ? Enums.Role.MEMBER : null, false));
    }

    @GetMapping("/{teamId}/join-requests")
    public List<Dtos.JoinRequestDto> pending(@PathVariable Long teamId) {
        return teamService.pendingRequestDtos(teamId, currentUser.require());
    }

    @PostMapping("/{teamId}/join-requests/{requestId}/approve")
    public ResponseEntity<Void> approve(@PathVariable Long teamId, @PathVariable Long requestId,
                                        @RequestBody(required = false) ApproveRequest req) {
        teamService.approve(teamId, requestId, req == null ? null : req.role(), currentUser.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/join-requests/{requestId}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long teamId, @PathVariable Long requestId) {
        teamService.reject(teamId, requestId, currentUser.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/members")
    public List<Dtos.MemberDto> members(@PathVariable Long teamId) {
        return teamService.memberDtos(teamId, currentUser.require());
    }

    @PatchMapping("/{teamId}/members/{userId}/role")
    public ResponseEntity<Void> assignRole(@PathVariable Long teamId, @PathVariable Long userId,
                                           @RequestBody RoleRequest req) {
        teamService.assignRole(teamId, userId, req.role(), currentUser.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/invite-code/reset")
    public Map<String, String> resetInvite(@PathVariable Long teamId) {
        String code = teamService.resetInviteCode(teamId, currentUser.require());
        return Map.of("inviteCode", code);
    }

    public record PatchTeamRequest(String name, String description, String themeColor, Integer iterationWeeks,
                                   Boolean joinApproval, String estimateUnit, tools.jackson.databind.JsonNode settings) {}
    public record MemberStatusRequest(String memberStatus, java.time.LocalDate memberStatusStart, java.time.LocalDate memberStatusEnd) {}

    @PatchMapping("/{teamId}")
    public Dtos.TeamDto patchTeam(@PathVariable Long teamId, @RequestBody PatchTeamRequest req) {
        AppUser u = currentUser.require();
        Team t = teamService.updateTeam(teamId, u, new TeamService.TeamPatch(
                req.name(), req.description(), req.themeColor(), req.iterationWeeks(),
                req.joinApproval(), req.estimateUnit(), req.settings()));
        return Dtos.TeamDto.of(t, Enums.Role.LEADER, true);
    }

    @PostMapping("/{teamId}/archive")
    public Dtos.TeamDto archive(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        Team t = teamService.archive(teamId, u);
        return Dtos.TeamDto.of(t, Enums.Role.LEADER, true);
    }

    @PostMapping("/{teamId}/leave")
    public ResponseEntity<Void> leave(@PathVariable Long teamId) {
        teamService.leave(teamId, currentUser.require());
        return ResponseEntity.noContent().build();
    }

    /** P05：组长移出成员（须先完成离组交接，否则 409 HANDOVER_PENDING）。 */
    @DeleteMapping("/{teamId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long teamId, @PathVariable Long userId) {
        teamService.removeMember(teamId, userId, currentUser.require());
        return ResponseEntity.noContent().build();
    }

    /** P05：待处理清单查询（移出/退出/停用前）。 */
    @GetMapping("/{teamId}/members/{userId}/handover-pending")
    public List<Map<String, Object>> handoverPending(@PathVariable Long teamId, @PathVariable Long userId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        if (!u.getId().equals(userId)) teamService.requireLeader(teamId, u);
        return handoverService.pending(teamId, userId).stream().map(HandoverService.PendingEntry::toMap).toList();
    }

    public record HandoverActionRequest(Long itemId, String action, Long targetUserId) {}
    public record HandoverRequest(List<HandoverActionRequest> actions) {}

    /** P05：逐条交接（TRANSFER 转交他人 / RETURN_TO_POOL 退回认领池）。 */
    @PostMapping("/{teamId}/members/{userId}/handover")
    public Map<String, Object> handover(@PathVariable Long teamId, @PathVariable Long userId,
                                        @RequestBody HandoverRequest req) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        if (!u.getId().equals(userId)) teamService.requireLeader(teamId, u);
        List<HandoverService.HandoverAction> actions = (req == null || req.actions() == null ? List.<HandoverActionRequest>of() : req.actions())
                .stream().map(a -> new HandoverService.HandoverAction(a.itemId(), a.action(), a.targetUserId())).toList();
        return handoverService.apply(teamId, userId, actions);
    }

    /** P05：一键全部退回认领池（验收人改为组长）。 */
    @PostMapping("/{teamId}/members/{userId}/handover/return-all")
    public Map<String, Object> handoverReturnAll(@PathVariable Long teamId, @PathVariable Long userId) {
        AppUser u = currentUser.require();
        teamService.requireMember(teamId, u);
        if (!u.getId().equals(userId)) teamService.requireLeader(teamId, u);
        return handoverService.returnAll(teamId, userId);
    }

    /** P19：请假期间到期事项清单。 */
    @GetMapping("/{teamId}/members/{userId}/leave-due-items")
    public List<Map<String, Object>> leaveDueItems(@PathVariable Long teamId, @PathVariable Long userId,
                                                   @RequestParam(required = false) java.time.LocalDate start,
                                                   @RequestParam(required = false) java.time.LocalDate end) {
        teamService.requireMember(teamId, currentUser.require());
        return handoverService.dueItemsDuring(teamId, userId, start, end);
    }

    @PatchMapping("/{teamId}/members/{userId}/status")
    public Dtos.MemberDto setMemberStatus(@PathVariable Long teamId, @PathVariable Long userId,
                                          @RequestBody MemberStatusRequest req) {
        AppUser u = currentUser.require();
        return teamService.setMemberStatusDto(teamId, userId, u, req.memberStatus(), req.memberStatusStart(), req.memberStatusEnd());
    }
}
