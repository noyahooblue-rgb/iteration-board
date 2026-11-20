package com.iterationboard.collab;

import com.iterationboard.common.Workdays;
import com.iterationboard.config.Dtos;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.item.ItemService;
import com.iterationboard.reminder.RiskService;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import com.iterationboard.team.TeamSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站会页：按成员组织——昨日进展（本人昨日登记的进展）、昨日完成、
 * 今日在手、阻塞项；外加当日站会备注。数据全部实时聚合，不另存站会快照。
 */
@Service
public class StandupService {
    private final TeamMemberRepo memberRepo;
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final ProgressRepo progressRepo;
    private final StandupNoteRepo noteRepo;
    private final TeamService teamService;
    private final ItemService itemService;

    public StandupService(TeamMemberRepo memberRepo, ItemRepo itemRepo, ItemOwnerRepo ownerRepo,
                          ProgressRepo progressRepo, StandupNoteRepo noteRepo,
                          TeamService teamService, ItemService itemService) {
        this.memberRepo = memberRepo;
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.progressRepo = progressRepo;
        this.noteRepo = noteRepo;
        this.teamService = teamService;
        this.itemService = itemService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> standup(Long teamId, AppUser user, LocalDate date) {
        TeamMember me = teamService.requireMember(teamId, user);
        Team team = me.getTeam();
        TeamSettings settings = TeamSettings.from(team);
        LocalDate prev = Workdays.previousWorkday(date, settings);
        List<Item> teamItems = itemRepo.findByTeamId(teamId).stream()
                .filter(i -> i.getDeletedAt() == null && i.getType() != ItemType.TASK)
                .toList();
        List<Long> itemIds = teamItems.stream().map(Item::getId).toList();
        List<ProgressUpdate> allProgress = itemIds.isEmpty() ? List.of() : progressRepo.findByItemIdIn(itemIds);
        Map<Long, Item> byId = new LinkedHashMap<>();
        teamItems.forEach(i -> byId.put(i.getId(), i));

        List<Map<String, Object>> members = new ArrayList<>();
        for (TeamMember m : memberRepo.findByTeamId(teamId)) {
            Long uid = m.getUser().getId();
            List<Item> owned = ownerRepo.findByUserId(uid).stream()
                    .map(o -> byId.get(o.getItem().getId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("userId", uid);
            mm.put("memberStatus", m.getMemberStatus());
            mm.put("yesterdayProgress", allProgress.stream()
                    .filter(p -> p.getAuthor().getId().equals(uid) && prev.equals(RiskService.dateOf(p.getCreatedAt())))
                    .map(Dtos.ProgressDto::of).toList());
            mm.put("yesterdayDone", owned.stream()
                    .filter(i -> i.getStatus() == ItemStatus.DONE && prev.equals(RiskService.dateOf(i.getStatusChangedAt())))
                    .map(i -> itemService.toDto(i, user)).toList());
            mm.put("todayItems", owned.stream()
                    .filter(i -> i.getStatus() == ItemStatus.IN_PROGRESS || i.getStatus() == ItemStatus.NOT_STARTED)
                    .map(i -> itemService.toDto(i, user)).toList());
            mm.put("blockedItems", owned.stream()
                    .filter(Item::isBlocked)
                    .filter(i -> i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED)
                    .map(i -> itemService.toDto(i, user)).toList());
            members.add(mm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        // P12：摘要「数据截至」时间戳（逐人卡片本身始终实时聚合）
        out.put("dataAsOf", java.time.Instant.now());
        out.put("previousWorkday", prev);
        out.put("members", members);
        out.put("note", noteRepo.findByTeamIdAndNoteDate(teamId, date).map(StandupNote::getContent).orElse(null));
        return out;
    }

    private final java.util.Map<Long, java.time.Instant> lastRefreshByTeam = new java.util.concurrent.ConcurrentHashMap<>();

    /** P12：组长/PO 刷新站会摘要（1 分钟节流）；逐人卡片走 standup() 实时聚合，此处只刷新摘要时间戳口径。 */
    @Transactional(readOnly = true)
    public Map<String, Object> refresh(Long teamId, AppUser user, LocalDate date) {
        TeamMember me = teamService.requireMember(teamId, user);
        if (me.getRole() != Role.LEADER && me.getRole() != Role.PO) {
            throw com.iterationboard.common.ApiException.forbidden("仅组长或 PO 可刷新站会摘要");
        }
        java.time.Instant now = java.time.Instant.now();
        java.time.Instant last = lastRefreshByTeam.get(teamId);
        if (last != null && last.plus(1, java.time.temporal.ChronoUnit.MINUTES).isAfter(now)) {
            throw new com.iterationboard.common.ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "REFRESH_THROTTLED", "站会摘要刷新过于频繁，请 1 分钟后再试");
        }
        lastRefreshByTeam.put(teamId, now);
        return standup(teamId, user, date);
    }

    @Transactional
    public Map<String, Object> saveNote(Long teamId, AppUser user, LocalDate date, String content) {
        TeamMember me = teamService.requireMember(teamId, user);
        StandupNote note = noteRepo.findByTeamIdAndNoteDate(teamId, date).orElseGet(() -> {
            StandupNote n = new StandupNote();
            n.setTeam(me.getTeam());
            n.setNoteDate(date);
            return n;
        });
        note.setContent(content == null ? "" : content);
        note.setUpdatedBy(user);
        note.setUpdatedAt(java.time.Instant.now());
        noteRepo.save(note);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("note", note.getContent());
        return out;
    }
}
