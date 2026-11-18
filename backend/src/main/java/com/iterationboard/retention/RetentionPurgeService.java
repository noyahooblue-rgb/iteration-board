package com.iterationboard.retention;

import com.iterationboard.repo.AuditLogRepo;
import com.iterationboard.repo.NotificationRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据留存物理清理：
 * 软删除进回收站的事项/报告满 {@code app.retention.trash-days} 天后物理删除；
 * 通知满 {@code notification-days} 天、审计日志满 {@code audit-days} 天物理删除。
 * 保留期均可配置；{@link #purge()} 可直接调用（定时任务与集成测试共用）。
 *
 * <p>实现口径：事项/报告的删除全程走 JDBC 按外键安全顺序逐表删，不经 JPA 实体删除——
 * 避免同一持久化上下文中残留的关联实体（如检查清单）在后续自动 flush 时
 * 引用已删实体而失败，也保证不留孤儿行。
 *
 * <p>保守口径（设计未写清处从严）：
 * <ul>
 *   <li>事项按「无子项残留」自叶向根反复推进：子项未删除、或子项已删除但尚未到期，
 *       父项本轮一律跳过并记日志，绝不提前删除子项、绝不误删未删除事项的子项。</li>
 *   <li>引用待删事项的通知不连带删除：仅把 notification.item_id 置空，
 *       通知本身仍按自己的 90 天口径清理。</li>
 *   <li>报告物理删除连带其版本快照行；报告行本身即墓碑行（周期唯一性），到期一并删除。</li>
 * </ul>
 */
@Service
public class RetentionPurgeService {
    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    private final NotificationRepo notificationRepo;
    private final AuditLogRepo auditLogRepo;
    private final JdbcTemplate jdbc;
    private final int trashDays;
    private final int notificationDays;
    private final int auditDays;

    public RetentionPurgeService(NotificationRepo notificationRepo, AuditLogRepo auditLogRepo, JdbcTemplate jdbc,
                                 @Value("${app.retention.trash-days:30}") int trashDays,
                                 @Value("${app.retention.notification-days:90}") int notificationDays,
                                 @Value("${app.retention.audit-days:180}") int auditDays) {
        this.notificationRepo = notificationRepo;
        this.auditLogRepo = auditLogRepo;
        this.jdbc = jdbc;
        this.trashDays = trashDays;
        this.notificationDays = notificationDays;
        this.auditDays = auditDays;
    }

    public record PurgeResult(int itemsPurged, int itemsSkipped, int reportsPurged,
                              long notificationsPurged, long auditLogsPurged) {}

    @Transactional
    public PurgeResult purge() {
        return purge(Instant.now());
    }

    @Transactional
    public PurgeResult purge(Instant now) {
        int[] items = purgeExpiredItems(now.minus(trashDays, ChronoUnit.DAYS));
        int reportsPurged = purgeExpiredReports(now.minus(trashDays, ChronoUnit.DAYS));
        long notifications = notificationRepo.deleteByCreatedAtBefore(now.minus(notificationDays, ChronoUnit.DAYS));
        long auditLogs = auditLogRepo.deleteByCreatedAtBefore(now.minus(auditDays, ChronoUnit.DAYS));
        log.info("留存清理完成：事项 {}（跳过 {}）、报告 {}、通知 {}、审计日志 {}",
                items[0], items[1], reportsPurged, notifications, auditLogs);
        return new PurgeResult(items[0], items[1], reportsPurged, notifications, auditLogs);
    }

    /** 自叶向根反复推进，直到没有可删事项为止；被未到期/未删除子项挡住的计入跳过并记日志。返回 {已删, 跳过}。 */
    private int[] purgeExpiredItems(Instant cutoff) {
        List<Long> candidates = new ArrayList<>(jdbc.queryForList(
                "SELECT id FROM item WHERE deleted_at < ?", Long.class, Timestamp.from(cutoff)));
        int purged = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Long itemId : new ArrayList<>(candidates)) {
                Integer childCount = jdbc.queryForObject(
                        "SELECT count(*) FROM item WHERE parent_id = ?", Integer.class, itemId);
                if (childCount != null && childCount == 0) {
                    purgeItemAssociations(itemId);
                    jdbc.update("DELETE FROM item WHERE id = ?", itemId);
                    candidates.remove(itemId);
                    purged++;
                    progress = true;
                }
            }
        }
        for (Long itemId : candidates) {
            log.warn("回收站事项 {} 本轮跳过物理删除：仍有子项残留（子项未删除或尚未到期）", itemId);
        }
        return new int[]{purged, candidates.size()};
    }

    /**
     * 按外键安全顺序清理事项的全部关联行（无 ON DELETE CASCADE，逐表显式删，保证不留孤儿）：
     * 检查清单、评论（含 @提及）、进展、时间线、负责人/协作者/被邀请人/关注者、标签关联、
     * 催办、导入批次行；通知仅解绑 item_id（见类注释保守口径）。
     */
    private void purgeItemAssociations(Long itemId) {
        jdbc.update("DELETE FROM comment_mention WHERE comment_id IN (SELECT id FROM comment WHERE item_id = ?)", itemId);
        jdbc.update("DELETE FROM comment WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM checklist_entry WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM progress_update WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM activity WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM item_owner WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM item_collaborator WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM item_invitee WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM item_watcher WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM item_label WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM nudge WHERE item_id = ?", itemId);
        jdbc.update("DELETE FROM import_batch_item WHERE item_id = ?", itemId);
        jdbc.update("UPDATE notification SET item_id = NULL WHERE item_id = ?", itemId);
    }

    private int purgeExpiredReports(Instant cutoff) {
        List<Long> expired = jdbc.queryForList(
                "SELECT id FROM report WHERE deleted_at < ?", Long.class, Timestamp.from(cutoff));
        for (Long reportId : expired) {
            jdbc.update("DELETE FROM report_version WHERE report_id = ?", reportId);
            jdbc.update("DELETE FROM report WHERE id = ?", reportId);
        }
        return expired.size();
    }
}
