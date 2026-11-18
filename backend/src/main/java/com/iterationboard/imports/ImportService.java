package com.iterationboard.imports;

import com.iterationboard.common.ApiException;
import com.iterationboard.domain.*;
import com.iterationboard.domain.Enums.*;
import com.iterationboard.repo.*;
import com.iterationboard.team.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入批次：导入的是任务。
 * 外部编号去重：同组 + 同系统名 + 同编号只更新外部字段（标题/描述/截止），不动负责人/状态/进展。
 * 整批撤销：24 小时内，发起人或组长；新建的软删、更新的回滚快照。
 */
@Service
public class ImportService {
    public static final int MAX_IMPORT_ROWS = 100;
    public static final java.time.Duration UNDO_WINDOW = java.time.Duration.ofHours(24);

    private final ImportBatchRepo batchRepo;
    private final ImportBatchItemRepo batchItemRepo;
    private final ItemRepo itemRepo;
    private final TeamRepo teamRepo;
    private final ActivityRepo activityRepo;
    private final TeamService teamService;

    public ImportService(ImportBatchRepo batchRepo, ImportBatchItemRepo batchItemRepo, ItemRepo itemRepo,
                         TeamRepo teamRepo, ActivityRepo activityRepo, TeamService teamService) {
        this.batchRepo = batchRepo;
        this.batchItemRepo = batchItemRepo;
        this.itemRepo = itemRepo;
        this.teamRepo = teamRepo;
        this.activityRepo = activityRepo;
        this.teamService = teamService;
    }

    public record ImportRow(String externalId, String title, String description, String priority,
                            LocalDate dueDate, String externalUrl, boolean externalClosed, Double estimate) {}

    public record ApplyResult(ImportBatch batch, List<Long> createdItemIds, List<Long> updatedItemIds, int skipped) {}

    /** 核心写入（导入确认与适配器同步共用）：systemName 为外部命名空间。 */
    @Transactional
    public ApplyResult applyImport(Long teamId, AppUser actor, String source, String systemName, List<ImportRow> rows) {
        Team team = teamService.requireTeam(teamId);
        ImportBatch batch = batchRepo.save(new ImportBatch(team, actor, source));
        List<Long> created = new ArrayList<>();
        List<Long> updated = new ArrayList<>();
        int skipped = 0;
        for (ImportRow row : rows) {
            if (row.title() == null || row.title().isBlank()) { skipped++; continue; }
            Item existing = row.externalId() == null || row.externalId().isBlank() ? null
                    : itemRepo.findFirstByTeamIdAndExternalSystemAndExternalId(teamId, systemName, row.externalId()).orElse(null);
            if (existing != null) {
                if (existing.getDeletedAt() != null) { skipped++; continue; }
                batchItemRepo.save(new ImportBatchItem(batch, existing, "UPDATE",
                        existing.getTitle(), existing.getDescription(), existing.getDueDate()));
                existing.setTitle(truncate(row.title(), 200));
                if (row.description() != null) existing.setDescription(row.description());
                if (row.dueDate() != null) existing.setDueDate(row.dueDate());
                if (row.externalUrl() != null) existing.setExternalUrl(row.externalUrl());
                if (row.externalClosed()) existing.setExternalClosed(true);
                existing.setImportBatchId(batch.getId());
                itemRepo.save(existing);
                updated.add(existing.getId());
                continue;
            }
            Item item = createTaskInternal(team, actor, row, source, systemName, batch.getId());
            batchItemRepo.save(new ImportBatchItem(batch, item, "CREATE", null, null, null));
            created.add(item.getId());
        }
        batch.setTotal(rows.size());
        batch.setCreatedCount(created.size());
        batch.setUpdatedCount(updated.size());
        batchRepo.save(batch);
        return new ApplyResult(batch, created, updated, skipped);
    }

    /** 内部建任务（不走 ItemService.create 的 PO 角色校验：调用方已按来源做过权限判定）。 */
    private Item createTaskInternal(Team team, AppUser actor, ImportRow row, String source, String systemName, Long batchId) {
        Team locked = teamRepo.findByIdForUpdate(team.getId()).orElseThrow(() -> ApiException.notFound("小组不存在"));
        long seq = locked.getItemSeq() + 1;
        locked.setItemSeq(seq);
        teamRepo.save(locked);
        Item item = new Item();
        item.setTeam(locked);
        item.setSeq(seq);
        item.setCode(locked.getCode() + "-" + seq);
        item.setType(ItemType.TASK);
        item.setTitle(truncate(row.title(), 200));
        item.setDescription(row.description());
        item.setPriority(parsePriority(row.priority()));
        // 契约 0.5.1：estimate 仅新建时落库；更新既有事项仍只覆盖外部字段（标题/描述/截止），不覆盖 estimate。
        if (row.estimate() != null) item.setEstimate(java.math.BigDecimal.valueOf(row.estimate()));
        item.setCreator(actor);
        item.setPo(actor);
        item.setDueDate(row.dueDate());
        item.setStatus(ItemStatus.NOT_STARTED);
        item.setProgressPercent(0);
        item.setSource(source);
        item.setExternalSystem(systemName);
        item.setExternalId(row.externalId());
        item.setExternalUrl(row.externalUrl());
        item.setExternalClosed(row.externalClosed());
        item.setImportBatchId(batchId);
        itemRepo.save(item);
        activityRepo.save(new Activity(item, actor, "CREATE", null, ItemStatus.NOT_STARTED.name(), "导入批次 #" + batchId));
        return item;
    }

    static Priority parsePriority(String p) {
        if (p == null) return Priority.P2;
        try { return Priority.valueOf(p.strip().toUpperCase()); }
        catch (Exception e) { return Priority.P2; }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    @Transactional(readOnly = true)
    public List<ImportBatch> list(Long teamId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        //：导入批次可见 = 发起人、组长、PO
        return batchRepo.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
                .filter(b -> me.getRole() == Role.LEADER || me.getRole() == Role.PO
                        || b.getCreator().getId().equals(user.getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ImportBatch get(Long teamId, Long batchId, AppUser user) {
        teamService.requireMember(teamId, user);
        return batchRepo.findById(batchId)
                .filter(b -> b.getTeam().getId().equals(teamId))
                .orElseThrow(() -> ApiException.notFound("导入批次不存在"));
    }

    /** 整批撤销：24 小时内，发起人或组长；超时拒绝。 */
    @Transactional
    public ImportBatch undo(Long teamId, Long batchId, AppUser user) {
        TeamMember me = teamService.requireMember(teamId, user);
        ImportBatch batch = get(teamId, batchId, user);
        boolean allowed = me.getRole() == Role.LEADER || batch.getCreator().getId().equals(user.getId());
        if (!allowed) throw ApiException.forbidden("只有批次发起人或组长可整批撤销");
        if (batch.getUndoneAt() != null) throw ApiException.validation("该批次已撤销");
        if (Instant.now().isAfter(batch.getCreatedAt().plus(UNDO_WINDOW))) {
            throw new ApiException(HttpStatus.CONFLICT, "UNDO_EXPIRED", "超过 24 小时，不能整批撤销");
        }
        for (ImportBatchItem bi : batchItemRepo.findByBatchId(batchId)) {
            Item item = bi.getItem();
            if ("CREATE".equals(bi.getAction())) {
                if (item.getDeletedAt() == null) {
                    item.setDeletedAt(Instant.now());
                    item.setDeletedById(user.getId());
                }
            } else {
                item.setTitle(bi.getPrevTitle());
                item.setDescription(bi.getPrevDescription());
                item.setDueDate(bi.getPrevDueDate());
            }
            itemRepo.save(item);
        }
        batch.setUndoneAt(Instant.now());
        batch.setUndoneBy(user);
        return batchRepo.save(batch);
    }
}
