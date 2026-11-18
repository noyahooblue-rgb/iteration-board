package com.iterationboard.notify;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.CurrentUser;
import com.iterationboard.domain.AppUser;
import com.iterationboard.domain.Notification;
import com.iterationboard.repo.NotificationRepo;
import com.iterationboard.repo.UserRepo;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 通知中心（契约 0.3.0）：列表 / 未读数 / 已读 / 全部已读 / SSE 订阅 / 通知偏好。 */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private final NotificationRepo notificationRepo;
    private final UserRepo userRepo;
    private final CurrentUser currentUser;
    private final SseRegistry sseRegistry;

    public NotificationController(NotificationRepo notificationRepo, UserRepo userRepo,
                                  CurrentUser currentUser, SseRegistry sseRegistry) {
        this.notificationRepo = notificationRepo;
        this.userRepo = userRepo;
        this.currentUser = currentUser;
        this.sseRegistry = sseRegistry;
    }

    // 读事务内映射：NotificationView.toMap 读 item/actor 等 LAZY 关联，无事务时生产路径 500（修订二端点探针发现，同 Team 口径）
    @Transactional(readOnly = true)
    @GetMapping("/notifications")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long teamId,
                                          @RequestParam(required = false) Boolean unreadOnly,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) Integer limit) {
        AppUser me = currentUser.require();
        List<Notification> all = teamId == null
                ? notificationRepo.findByRecipientIdOrderByCreatedAtDesc(me.getId())
                : notificationRepo.findByRecipientIdAndTeamIdOrderByCreatedAtDesc(me.getId(), teamId);
        return all.stream()
                .filter(n -> unreadOnly == null || !unreadOnly || n.getReadAt() == null)
                .filter(n -> category == null || category.equals(NotificationView.categoryOf(n.getType())))
                .limit(limit == null ? 100 : Math.min(Math.max(limit, 1), 200))
                .map(NotificationView::toMap)
                .toList();
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount(@RequestParam(required = false) Long teamId) {
        AppUser me = currentUser.require();
        long count = teamId == null
                ? notificationRepo.countByRecipientIdAndReadAtIsNull(me.getId())
                : notificationRepo.countByRecipientIdAndTeamIdAndReadAtIsNull(me.getId(), teamId);
        return Map.of("unread", count);
    }

    @PostMapping("/notifications/{notificationId}/read")
    @Transactional
    public Map<String, Object> markRead(@PathVariable Long notificationId) {
        AppUser me = currentUser.require();
        Notification n = notificationRepo.findById(notificationId)
                .orElseThrow(() -> ApiException.notFound("通知不存在"));
        if (!n.getRecipient().getId().equals(me.getId())) throw ApiException.forbidden("只能标记自己的通知");
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            notificationRepo.save(n);
        }
        return NotificationView.toMap(n);
    }

    public record ReadAllRequest(Long teamId) { }

    @PostMapping("/notifications/read-all")
    @Transactional
    public Map<String, Object> markAllRead(@RequestBody(required = false) ReadAllRequest req) {
        AppUser me = currentUser.require();
        Long teamId = req == null ? null : req.teamId();
        List<Notification> all = teamId == null
                ? notificationRepo.findByRecipientIdOrderByCreatedAtDesc(me.getId())
                : notificationRepo.findByRecipientIdAndTeamIdOrderByCreatedAtDesc(me.getId(), teamId);
        Instant now = Instant.now();
        int marked = 0;
        for (Notification n : all) {
            if (n.getReadAt() == null) {
                n.setReadAt(now);
                marked++;
            }
        }
        notificationRepo.saveAll(all);
        return Map.of("marked", marked);
    }

    @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        AppUser me = currentUser.require();
        return sseRegistry.subscribe(me.getId());
    }

    @GetMapping("/auth/notification-prefs")
    public Map<String, Boolean> getPrefs() {
        return NotificationPrefs.of(currentUser.require());
    }

    @PatchMapping("/auth/notification-prefs")
    @Transactional
    public Map<String, Boolean> patchPrefs(@RequestBody Map<String, Boolean> patch) {
        AppUser me = currentUser.require();
        me.setPreferencesJson(NotificationPrefs.merge(me, patch));
        userRepo.save(me);
        return NotificationPrefs.of(me);
    }
}
