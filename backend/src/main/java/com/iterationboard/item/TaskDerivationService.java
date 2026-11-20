package com.iterationboard.item;

import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.ItemRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 任务状态与进度推算（state-machine.md「任务状态推算」节）：
 * 取消/已删除的故事不计；无故事或全未开始→NOT_STARTED；有进行中/待验收或部分完成→IN_PROGRESS；
 * 非取消故事全部 DONE 且至少一条→PENDING_ACCEPTANCE（内部存储映射 IN_REVIEW），并给 poId 建一条通知。
 * DONE/CANCELLED 只由 PO 的 APPROVE/CANCEL 产生，推算不覆盖。
 */
@Service
public class TaskDerivationService {
    private final ItemRepo itemRepo;
    private final NotifyService notifyService;
    private final com.iterationboard.repo.TeamMemberRepo memberRepo;

    public TaskDerivationService(ItemRepo itemRepo, NotifyService notifyService,
                                 com.iterationboard.repo.TeamMemberRepo memberRepo) {
        this.itemRepo = itemRepo;
        this.notifyService = notifyService;
        this.memberRepo = memberRepo;
    }

    public static TaskStatus toTaskStatus(Item task) {
        return switch (task.getStatus()) {
            case NOT_STARTED, UNCLAIMED -> TaskStatus.NOT_STARTED;
            case IN_PROGRESS -> TaskStatus.IN_PROGRESS;
            case IN_REVIEW -> TaskStatus.PENDING_ACCEPTANCE;
            case DONE -> TaskStatus.DONE;
            case CANCELLED -> TaskStatus.CANCELLED;
        };
    }

    @Transactional
    public void recalc(Item task) {
        if (task == null || task.getType() != ItemType.TASK) return;
        if (task.getStatus() == ItemStatus.DONE || task.getStatus() == ItemStatus.CANCELLED) return;
        List<Item> stories = itemRepo.findByParentId(task.getId()).stream()
                .filter(s -> s.getDeletedAt() == null && s.getStatus() != ItemStatus.CANCELLED)
                .toList();
        TaskStatus before = toTaskStatus(task);
        TaskStatus derived;
        if (stories.isEmpty()) {
            derived = TaskStatus.NOT_STARTED;
        } else if (stories.stream().allMatch(s -> s.getStatus() == ItemStatus.DONE)) {
            derived = TaskStatus.PENDING_ACCEPTANCE;
        } else if (stories.stream().anyMatch(s -> s.getStatus() == ItemStatus.IN_PROGRESS
                || s.getStatus() == ItemStatus.IN_REVIEW || s.getStatus() == ItemStatus.DONE)) {
            derived = TaskStatus.IN_PROGRESS;
        } else {
            derived = TaskStatus.NOT_STARTED;
        }
        task.setStatus(switch (derived) {
            case NOT_STARTED -> ItemStatus.NOT_STARTED;
            case IN_PROGRESS -> ItemStatus.IN_PROGRESS;
            case PENDING_ACCEPTANCE -> ItemStatus.IN_REVIEW;
            case DONE -> ItemStatus.DONE;
            case CANCELLED -> ItemStatus.CANCELLED;
        });
        task.setProgressPercent(progressOf(stories));
        itemRepo.save(task);
        if (derived == TaskStatus.PENDING_ACCEPTANCE && before != TaskStatus.PENDING_ACCEPTANCE) {
            // P17：故事全部完成 → 任务进入待验收，通知任务验收人（小组设置「任务默认验收角色」，默认组长）；
            // 任务 PO 与验收人不同人时一并通知 PO（保持既有口径）。
            AppUser acceptor = resolveTaskAcceptor(task);
            if (acceptor != null) {
                notifyService.notify(acceptor, "TASK_PENDING_ACCEPTANCE", task, null,
                        "任务「" + task.getTitle() + "」的故事已全部完成，待你验收");
            }
            if (task.getPo() != null && (acceptor == null || !task.getPo().getId().equals(acceptor.getId()))) {
                notifyService.notify(task.getPo(), "TASK_PENDING_ACCEPTANCE", task, null,
                        "任务「" + task.getTitle() + "」的故事已全部完成，待验收");
            }
        }
    }

    private AppUser resolveTaskAcceptor(Item task) {
        String role = com.iterationboard.team.TeamSettings.from(task.getTeam()).taskAcceptorRole;
        return memberRepo.findByTeamId(task.getTeam().getId()).stream()
                .filter(m -> m.getRole().name().equals(role))
                .map(TeamMember::getUser).findFirst().orElse(null);
    }

    /** 进度：故事点加权；总点为 0 时按件数。 */
    static int progressOf(List<Item> stories) {
        if (stories.isEmpty()) return 0;
        BigDecimal total = stories.stream().map(s -> s.getEstimate() == null ? BigDecimal.ZERO : s.getEstimate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() > 0) {
            BigDecimal done = stories.stream()
                    .filter(s -> s.getStatus() == ItemStatus.DONE)
                    .map(s -> s.getEstimate() == null ? BigDecimal.ZERO : s.getEstimate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            return done.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.HALF_UP).intValue();
        }
        long done = stories.stream().filter(s -> s.getStatus() == ItemStatus.DONE).count();
        return (int) Math.round(done * 100.0 / stories.size());
    }
}
