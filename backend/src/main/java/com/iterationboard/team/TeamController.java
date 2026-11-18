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
    private final CurrentUser currentUser;

    public TeamController(TeamService teamService, CurrentUser currentUser) {
        this.teamService = teamService;
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

    @PatchMapping("/{teamId}/members/{userId}/status")
    public Dtos.MemberDto setMemberStatus(@PathVariable Long teamId, @PathVariable Long userId,
                                          @RequestBody MemberStatusRequest req) {
        AppUser u = currentUser.require();
        return teamService.setMemberStatusDto(teamId, userId, u, req.memberStatus(), req.memberStatusStart(), req.memberStatusEnd());
    }
}
