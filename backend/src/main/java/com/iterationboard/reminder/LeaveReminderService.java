package com.iterationboard.reminder;

import com.iterationboard.common.PlatformTime;
import com.iterationboard.common.Workdays;
import com.iterationboard.domain.Enums.Role;
import com.iterationboard.domain.Team;
import com.iterationboard.domain.TeamMember;
import com.iterationboard.item.NotifyService;
import com.iterationboard.repo.TeamMemberRepo;
import com.iterationboard.repo.TeamRepo;
import com.iterationboard.team.TeamSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * P19 请假提醒：请假（memberStatus=LEAVE）开始前一个工作日，提醒本人与本组组长。
 * 「前一个工作日」口径：今天是工作日，且今天之后的下一个工作日恰为请假开始日。
 * 由 ScheduledJobs 每日 9:00（PlatformTime 平台时区）触发；同日重复执行由通知合并/去重口径兜底（类型+事项为空，按内容逐日一条靠 cron 每日一次保证）。
 */
@Service
public class LeaveReminderService {
    private final TeamRepo teamRepo;
    private final TeamMemberRepo memberRepo;
    private final NotifyService notifyService;
    private final PlatformTime platformTime;

    public LeaveReminderService(TeamRepo teamRepo, TeamMemberRepo memberRepo,
                                NotifyService notifyService, PlatformTime platformTime) {
        this.teamRepo = teamRepo;
        this.memberRepo = memberRepo;
        this.notifyService = notifyService;
        this.platformTime = platformTime;
    }

    @Transactional
    public int remindAll() {
        LocalDate today = platformTime.today();
        int sent = 0;
        for (Team team : teamRepo.findAll()) {
            if (!"ACTIVE".equals(team.getStatus())) continue;
            sent += remindTeam(team, today);
        }
        return sent;
    }

    @Transactional
    public int remindTeam(Team team, LocalDate today) {
        TeamSettings settings = TeamSettings.from(team);
        if (!Workdays.isWorkday(today, settings)) return 0;
        LocalDate nextWorkday = today.plusDays(1);
        while (!Workdays.isWorkday(nextWorkday, settings)) nextWorkday = nextWorkday.plusDays(1);
        List<TeamMember> members = memberRepo.findByTeamId(team.getId());
        List<TeamMember> leaders = members.stream().filter(m -> m.getRole() == Role.LEADER).toList();
        int sent = 0;
        for (TeamMember m : members) {
            if (!"LEAVE".equals(m.getMemberStatus()) || m.getStatusStart() == null) continue;
            if (!nextWorkday.equals(m.getStatusStart())) continue;
            String content = "你将于 " + m.getStatusStart() + " 开始请假，请提前安排好请假期间到期事项的转交";
            notifyService.notifyInTeam(m.getUser(), "REMINDER_LEAVE", null, team, null, content, true);
            sent++;
            for (TeamMember leader : leaders) {
                if (leader.getUser().getId().equals(m.getUser().getId())) continue;
                notifyService.notifyInTeam(leader.getUser(), "REMINDER_LEAVE", null, team, null,
                        "成员 " + m.getUser().getDisplayName() + " 将于 " + m.getStatusStart() + " 开始请假，请留意其事项交接");
                sent++;
            }
        }
        return sent;
    }
}
