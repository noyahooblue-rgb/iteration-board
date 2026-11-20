package com.iterationboard.team;

import com.iterationboard.common.ApiException;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P05 离组交接：成员被移出 / 主动退出 / 账号停用前，必须先处理其在该小组的待处理事项：
 *   OWNED    其负责（含共同负责，将自动只摘除本人份额时用 TRANSFER/RETURN 处理）的未完成事项；
 *   ACCEPTOR 待其验收的事项（故事/独立事项 acceptor=本人且 IN_REVIEW；任务 po=本人且待验收）；
 *   BATCH    其发出的「每人一份」批次中尚未完成的事项（不含已在 OWNED 中列出的）。
 * 共同负责（collaborator 协作人）不阻塞：移除/退出/停用时自动摘除。
 * 交接动作：TRANSFER 逐条转交他人；RETURN_TO_POOL 一键退回认领池（清空负责人、状态回待认领、验收人改为组长）。
 */
@Service
public class HandoverService {
    private final ItemRepo itemRepo;
    private final ItemOwnerRepo ownerRepo;
    private final CollaboratorRepo collaboratorRepo;
    private final DispatchBatchRepo batchRepo;
    private final TeamMemberRepo memberRepo;
    private final UserRepo userRepo;

    public HandoverService(ItemRepo itemRepo, ItemOwnerRepo ownerRepo, CollaboratorRepo collaboratorRepo,
                           DispatchBatchRepo batchRepo, TeamMemberRepo memberRepo, UserRepo userRepo) {
        this.itemRepo = itemRepo;
        this.ownerRepo = ownerRepo;
        this.collaboratorRepo = collaboratorRepo;
        this.batchRepo = batchRepo;
        this.memberRepo = memberRepo;
        this.userRepo = userRepo;
    }

    public record PendingEntry(Long itemId, String code, String title, String kind, String status, Long batchId) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", itemId);
            m.put("code", code);
            m.put("title", title);
            m.put("kind", kind);
            m.put("status", status);
            m.put("batchId", batchId);
            return m;
        }
    }

    private static boolean open(Item i) {
        return i.getDeletedAt() == null && i.getStatus() != ItemStatus.DONE && i.getStatus() != ItemStatus.CANCELLED;
    }

    @Transactional(readOnly = true)
    public List<PendingEntry> pending(Long teamId, Long userId) {
        List<Item> items = itemRepo.findByTeamId(teamId);
        java.util.Set<Long> ownedIds = new java.util.HashSet<>();
        ownerRepo.findByUserId(userId).forEach(o -> ownedIds.add(o.getItem().getId()));
        java.util.Set<Long> batchIds = new java.util.HashSet<>();
        batchRepo.findAll().stream()
                .filter(b -> b.getTeam().getId().equals(teamId) && b.getCreator().getId().equals(userId))
                .forEach(b -> batchIds.add(b.getId()));
        List<PendingEntry> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Item i : items) {
            if (!open(i)) continue;
            if (ownedIds.contains(i.getId())) {
                out.add(new PendingEntry(i.getId(), i.getCode(), i.getTitle(), "OWNED", i.getStatus().name(), i.getBatchId()));
                seen.add(i.getId());
            } else if ((i.getAcceptor() != null && i.getAcceptor().getId().equals(userId) && i.getStatus() == ItemStatus.IN_REVIEW)
                    || (i.getType() == ItemType.TASK && i.getPo() != null && i.getPo().getId().equals(userId)
                        && i.getStatus() == ItemStatus.IN_REVIEW)) {
                out.add(new PendingEntry(i.getId(), i.getCode(), i.getTitle(), "ACCEPTOR", i.getStatus().name(), i.getBatchId()));
                seen.add(i.getId());
            } else if (i.getBatchId() != null && batchIds.contains(i.getBatchId())) {
                out.add(new PendingEntry(i.getId(), i.getCode(), i.getTitle(), "BATCH", i.getStatus().name(), i.getBatchId()));
                seen.add(i.getId());
            }
        }
        return out;
    }

    /** 阻塞校验：有待处理事项时抛 409 HANDOVER_PENDING（附清单条数）。 */
    public void assertCleared(Long teamId, Long userId) {
        List<PendingEntry> pending = pending(teamId, userId);
        if (!pending.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "HANDOVER_PENDING",
                    "该成员在小组内还有 " + pending.size() + " 条待处理事项，须先完成离组交接",
                    Map.of("pendingCount", pending.size(),
                            "itemIds", pending.stream().map(PendingEntry::itemId).toList()));
        }
    }

    public record HandoverAction(Long itemId, String action, Long targetUserId) {}

    @Transactional
    public Map<String, Object> apply(Long teamId, Long userId, List<HandoverAction> actions) {
        if (actions == null || actions.isEmpty()) throw ApiException.validation("actions 不能为空");
        AppUser leader = leaderOf(teamId);
        for (HandoverAction a : actions) {
            Item item = itemRepo.findByIdAndTeamId(a.itemId(), teamId)
                    .filter(i -> i.getDeletedAt() == null)
                    .orElseThrow(() -> ApiException.notFound("事项不存在：" + a.itemId()));
            if ("RETURN_TO_POOL".equals(a.action())) {
                returnToPool(item, leader);
            } else if ("TRANSFER".equals(a.action())) {
                if (a.targetUserId() == null) throw ApiException.validation("转交必须指定 targetUserId");
                AppUser target = requireTeamUser(teamId, a.targetUserId());
                transfer(item, userId, target, leader);
            } else {
                throw ApiException.validation("action 须为 TRANSFER / RETURN_TO_POOL");
            }
            itemRepo.save(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("remainingPending", pending(teamId, userId).size());
        return out;
    }

    @Transactional
    public Map<String, Object> returnAll(Long teamId, Long userId) {
        AppUser leader = leaderOf(teamId);
        for (PendingEntry e : pending(teamId, userId)) {
            Item item = itemRepo.findByIdAndTeamId(e.itemId(), teamId).orElse(null);
            if (item == null) continue;
            if ("ACCEPTOR".equals(e.kind())) {
                // 待验收事项退回认领池口径：验收人改为组长，事项本身不回池（已在验收环节）
                if (item.getType() == ItemType.TASK) item.setPo(leader); else item.setAcceptor(leader);
            } else {
                returnToPool(item, leader);
            }
            itemRepo.save(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("remainingPending", pending(teamId, userId).size());
        return out;
    }

    private void returnToPool(Item item, AppUser leader) {
        ownerRepo.deleteByItemId(item.getId());
        ownerRepo.flush();
        item.setAssignedDirectly(false);
        if (item.getType() != ItemType.TASK) {
            item.setStatus(ItemStatus.UNCLAIMED);
            item.setAcceptor(leader);
        }
    }

    private void transfer(Item item, Long fromUserId, AppUser target, AppUser leader) {
        List<ItemOwner> owners = ownerRepo.findByItemId(item.getId());
        boolean isOwner = owners.stream().anyMatch(o -> o.getUser().getId().equals(fromUserId));
        if (isOwner) {
            boolean targetAlready = owners.stream().anyMatch(o -> o.getUser().getId().equals(target.getId()));
            ownerRepo.deleteByItemId(item.getId());
            ownerRepo.flush();
            for (ItemOwner o : owners) {
                if (o.getUser().getId().equals(fromUserId)) {
                    if (!targetAlready) ownerRepo.save(new ItemOwner(item, target));
                } else {
                    ownerRepo.save(new ItemOwner(item, o.getUser()));
                }
            }
            return;
        }
        if (item.getType() == ItemType.TASK && item.getPo() != null && item.getPo().getId().equals(fromUserId)) {
            item.setPo(target);
            return;
        }
        if (item.getAcceptor() != null && item.getAcceptor().getId().equals(fromUserId)) {
            item.setAcceptor(target);
            return;
        }
        // BATCH 条目：批次发出人的事项负责人即接收人时按负责人转交；否则仅变更批次口径无落点，按退回认领池处理
        returnToPool(item, leader);
    }

    private AppUser leaderOf(Long teamId) {
        return memberRepo.findByTeamId(teamId).stream()
                .filter(m -> m.getRole() == Role.LEADER)
                .map(TeamMember::getUser).findFirst()
                .orElseThrow(() -> ApiException.validation("小组没有组长，无法完成交接"));
    }

    private AppUser requireTeamUser(Long teamId, Long userId) {
        return userRepo.findById(userId)
                .filter(u -> memberRepo.findByTeamIdAndUserId(teamId, u.getId()).isPresent())
                .orElseThrow(() -> ApiException.validation("交接对象必须是小组成员"));
    }

    /** P19：请假期间（[start,end]）到期且未完成的负责事项清单，设置请假时提示转交。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dueItemsDuring(Long teamId, Long userId,
                                                    java.time.LocalDate start, java.time.LocalDate end) {
        java.util.Set<Long> ownedIds = new java.util.HashSet<>();
        ownerRepo.findByUserId(userId).forEach(o -> ownedIds.add(o.getItem().getId()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item i : itemRepo.findByTeamId(teamId)) {
            if (!open(i) || !ownedIds.contains(i.getId()) || i.getDueDate() == null) continue;
            if (start != null && i.getDueDate().isBefore(start)) continue;
            if (end != null && i.getDueDate().isAfter(end)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemId", i.getId());
            m.put("code", i.getCode());
            m.put("title", i.getTitle());
            m.put("status", i.getStatus().name());
            m.put("dueDate", i.getDueDate());
            out.add(m);
        }
        return out;
    }

    /** 共同负责自动摘除（移除/退出/停用时调用，不阻塞）。 */
    @Transactional
    public void removeCollaborations(Long teamId, Long userId) {
        for (Item i : itemRepo.findByTeamId(teamId)) {
            collaboratorRepo.findByItemId(i.getId()).stream()
                    .filter(c -> c.getUser().getId().equals(userId))
                    .forEach(collaboratorRepo::delete);
        }
    }
}
