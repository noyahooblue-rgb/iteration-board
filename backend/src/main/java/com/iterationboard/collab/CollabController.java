package com.iterationboard.collab;

import com.iterationboard.common.PlatformTime;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.reminder.ReminderService;
import com.iterationboard.team.TeamService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** 站会 / 总览 / 立即扫描（M3 协同端点）。 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
public class CollabController {
    private final StandupService standupService;
    private final OverviewService overviewService;
    private final ReminderService reminderService;
    private final TeamService teamService;
    private final CurrentUser currentUser;
    private final PlatformTime platformTime;

    public CollabController(StandupService standupService, OverviewService overviewService,
                            ReminderService reminderService, TeamService teamService, CurrentUser currentUser, PlatformTime platformTime) {
        this.standupService = standupService;
        this.overviewService = overviewService;
        this.reminderService = reminderService;
        this.teamService = teamService;
        this.currentUser = currentUser;
        this.platformTime = platformTime;
    }

    public record NoteRequest(LocalDate date, String content) {}

    @GetMapping("/standup")
    public Map<String, Object> standup(@PathVariable Long teamId,
                                       @RequestParam(required = false) LocalDate date) {
        AppUser u = currentUser.require();
        return standupService.standup(teamId, u, date == null ? platformTime.today() : date);
    }

    /** P12：站会摘要刷新（组长/PO，1 分钟节流）。 */
    @PostMapping("/standup/refresh")
    public Map<String, Object> refreshStandup(@PathVariable Long teamId,
                                              @RequestParam(required = false) LocalDate date) {
        AppUser u = currentUser.require();
        return standupService.refresh(teamId, u, date == null ? platformTime.today() : date);
    }

    @PutMapping("/standup/note")
    public Map<String, Object> saveNote(@PathVariable Long teamId, @RequestBody NoteRequest req) {
        AppUser u = currentUser.require();
        return standupService.saveNote(teamId, u, req.date() == null ? platformTime.today() : req.date(), req.content());
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        return overviewService.overview(teamId, u);
    }

    @PostMapping("/reminders/scan")
    public Map<String, Object> scanReminders(@PathVariable Long teamId) {
        AppUser u = currentUser.require();
        teamService.requireLeader(teamId, u);
        int generated = reminderService.scanTeam(teamService.requireTeam(teamId), platformTime.today());
        return Map.of("generated", generated);
    }
}
