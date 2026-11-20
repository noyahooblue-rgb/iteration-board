package com.iterationboard.item;

import com.iterationboard.domain.*;
import com.iterationboard.notify.NotificationPrefs;
import com.iterationboard.notify.NotificationView;
import com.iterationboard.notify.SseRegistry;
import com.iterationboard.repo.NotificationRepo;
import org.springframework.stereotype.Service;

/**
 * 站内通知写入 + SSE 实时推送。
 * 规则：不给自己发通知（唯一例外：同步连败通知经 includeSelf 豁免，见下）；接收人关闭对应类别时不生成（ASSIGNMENT / NUDGE 不可关闭）；
 * 通知带小组维度，供通知中心按组过滤。
 */
@Service
public class NotifyService {
    private final NotificationRepo notificationRepo;
    private final SseRegistry sseRegistry;

    public NotifyService(NotificationRepo notificationRepo, SseRegistry sseRegistry) {
        this.notificationRepo = notificationRepo;
        this.sseRegistry = sseRegistry;
    }

    public void notify(AppUser recipient, String type, Item item, AppUser actor, String content) {
        notifyInTeam(recipient, type, item, item == null ? null : item.getTeam(), actor, content);
    }

    /** 无关联事项的通知（如入组审批、迭代事件）显式带小组。 */
    public void notifyInTeam(AppUser recipient, String type, Item item, Team team, AppUser actor, String content) {
        notifyInTeam(recipient, type, item, team, actor, content, false);
    }

    /**
     * includeSelf=true 时豁免「不给自己发通知」：仅供明确要求通知触发者本人的路径使用
     * （yefqkvf3 待问 1：适配器同步连续失败通知所有组长，含触发者本人，见 AdapterService.notifyLeader）。
     * 通知偏好层不变：接收人关闭对应类别时仍不生成。
     */
    public void notifyInTeam(AppUser recipient, String type, Item item, Team team, AppUser actor, String content,
                             boolean includeSelf) {
        notifyInTeam(recipient, type, item, team, actor, content, includeSelf, null);
    }

    /** refId：平台级对象的引用 id（如反馈 id），供通知点击跳转定位；事项类通知传 null。 */
    public void notifyInTeam(AppUser recipient, String type, Item item, Team team, AppUser actor, String content,
                             boolean includeSelf, Long refId) {
        if (recipient == null) return;
        if (!includeSelf && actor != null && recipient.getId().equals(actor.getId())) return;
        if (!NotificationPrefs.enabled(recipient, NotificationView.categoryOf(type))) return;
        // P25 精简通知：开启后只收指派/邀请认领/@/催/待验收/加入申请类
        if (NotificationPrefs.compactEnabled(recipient) && !NotificationPrefs.compactAllows(type)) return;
        String safeContent = content == null ? null : (content.length() > 500 ? content.substring(0, 500) : content);
        // P25 合并：同一事项、同类型、同一接收人在 5 分钟内的多条变化合并为一条（更新内容与时间，不新增）
        if (item != null) {
            var recent = notificationRepo.findRecent(recipient.getId(), type, item.getId(),
                    java.time.Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES));
            if (!recent.isEmpty()) {
                Notification existing = recent.get(recent.size() - 1);
                existing.setContent(safeContent);
                existing.setCreatedAt(java.time.Instant.now());
                if (refId != null) existing.setRefId(refId);
                notificationRepo.save(existing);
                pushNotification(existing);
                return;
            }
        }
        Notification n = new Notification(recipient, type, item, actor, safeContent);
        n.setTeam(team);
        n.setRefId(refId);
        notificationRepo.save(n);
        pushNotification(n);
    }

    /**
     * SSE 实时推送（走「用户频道」——按接收人推送，平台通知同样实时到达，排查项 1）。
     * P2c 裁定：增量带 unreadCount，按每个连接订阅时的当前视图团队口径计算
     * （当前团队未读 + 平台未读，与铃铛面板同一谓词）；未带团队的连接按全部未读口径。
     */
    private void pushNotification(Notification n) {
        Long recipientId = n.getRecipient().getId();
        sseRegistry.push(recipientId, "notification", viewTeamId -> {
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>(NotificationView.toMap(n));
            long unread = viewTeamId != null
                    ? notificationRepo.countCurrentUnread(recipientId, viewTeamId)
                    : notificationRepo.countByRecipientIdAndReadAtIsNull(recipientId);
            payload.put("unreadCount", unread);
            return payload;
        });
    }
}
