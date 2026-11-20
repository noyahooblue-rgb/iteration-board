package com.iterationboard.notify;

import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Notification;
import com.iterationboard.repo.NotificationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知「当前视图」查询口径（P2c 裁定 zu5skyzg，A+C 合并方案）：
 * 当前视图 = 当前团队的通知 + 平台通知（team 为空），按时间混排。
 * 铃铛面板条目、角标未读数、标签页标题 (n)、红点 favicon 全部取自本服务的同一谓词，
 * 前端不再自行过滤或计算，保证 角标 == 面板默认视图未读数 恒等。
 */
@Service
public class NotificationQueryService {
    private final NotificationRepo notificationRepo;

    public NotificationQueryService(NotificationRepo notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    /** 当前视图：{ items, unreadCount }。items 仍可按 unreadOnly / category / limit 做展示过滤，
     *  unreadCount 恒为当前视图全部未读数（不受展示过滤影响），即角标口径。 */
    @Transactional(readOnly = true)
    public Map<String, Object> currentView(AppUser me, Long teamId, Boolean unreadOnly,
                                           String category, Integer limit) {
        List<Notification> all = notificationRepo.findCurrentView(me.getId(), teamId);
        long unreadCount = all.stream().filter(n -> n.getReadAt() == null).count();
        List<Map<String, Object>> items = all.stream()
                .filter(n -> unreadOnly == null || !unreadOnly || n.getReadAt() == null)
                .filter(n -> category == null || category.equals(NotificationView.categoryOf(n.getType())))
                .limit(limit == null ? 100 : Math.min(Math.max(limit, 1), 200))
                .map(NotificationView::toMap)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("unreadCount", unreadCount);
        return out;
    }

    /** 当前视图未读数（与 currentView 同一谓词，供 unread-count 接口与 SSE 增量共用）。 */
    public long unreadCountCurrent(Long recipientId, Long teamId) {
        return notificationRepo.countCurrentUnread(recipientId, teamId);
    }

    /** 团队切换器：{ teams: {teamId: 未读数}, platform: 平台通知未读数 }。 */
    @Transactional(readOnly = true)
    public Map<String, Object> unreadCountsByTeam(AppUser me) {
        Map<String, Long> teams = new LinkedHashMap<>();
        for (Object[] row : notificationRepo.countUnreadByTeam(me.getId())) {
            teams.put(String.valueOf(row[0]), (Long) row[1]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teams", teams);
        out.put("platform", notificationRepo.countByRecipientIdAndTeamIsNullAndReadAtIsNull(me.getId()));
        return out;
    }
}
