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
        if (recipient == null) return;
        if (!includeSelf && actor != null && recipient.getId().equals(actor.getId())) return;
        if (!NotificationPrefs.enabled(recipient, NotificationView.categoryOf(type))) return;
        Notification n = new Notification(recipient, type, item, actor,
                content == null ? null : (content.length() > 500 ? content.substring(0, 500) : content));
        n.setTeam(team);
        notificationRepo.save(n);
        sseRegistry.push(recipient.getId(), "notification", NotificationView.toMap(n));
    }
}
