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
    private final NotificationQueryService queryService;

    public NotificationController(NotificationRepo notificationRepo, UserRepo userRepo,
                                  CurrentUser currentUser, SseRegistry sseRegistry,
                                  NotificationQueryService queryService) {
        this.notificationRepo = notificationRepo;
        this.userRepo = userRepo;
        this.currentUser = currentUser;
        this.sseRegistry = sseRegistry;
        this.queryService = queryService;
    }

    // 读事务内映射：NotificationView.toMap 读 item/actor 等 LAZY 关联，无事务时生产路径 500（修订二端点探针发现，同 Team 口径）
    // scope=current（P2c 裁定）：返回 { items, unreadCount }——当前团队通知 + 平台通知混排，
    // 角标/面板/标题共用此口径；不带 scope 时保持旧列表形状（全部或单团队）。
    @Transactional(readOnly = true)
    @GetMapping("/notifications")
    public Object list(@RequestParam(required = false) Long teamId,
                       @RequestParam(required = false) Boolean unreadOnly,
                       @RequestParam(required = false) String category,
                       @RequestParam(required = false) Integer limit,
                       @RequestParam(required = false) String scope) {
        AppUser me = currentUser.require();
        if ("current".equals(scope)) {
            if (teamId == null) throw ApiException.validation("scope=current 需要 teamId（当前团队）");
            return queryService.currentView(me, teamId, unreadOnly, category, limit);
        }
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

    /** 带 teamId 时为「当前视图」口径（当前团队 + 平台，与铃铛面板同一谓词）；不带时为全部未读。 */
    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount(@RequestParam(required = false) Long teamId) {
        AppUser me = currentUser.require();
        long count = teamId == null
                ? notificationRepo.countByRecipientIdAndReadAtIsNull(me.getId())
                : queryService.unreadCountCurrent(me.getId(), teamId);
        return Map.of("unread", count);
    }

    /** 团队切换器口径：各团队各自未读数 + 平台未读数（裁定规则 3）。 */
    @GetMapping("/notifications/unread-counts-by-team")
    public Map<String, Object> unreadCountsByTeam() {
        return queryService.unreadCountsByTeam(currentUser.require());
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

    /** 带 teamId 时只作用于当前视图（当前团队 + 平台，裁定规则 6）；不带时作用于全部。 */
    @PostMapping("/notifications/read-all")
    @Transactional
    public Map<String, Object> markAllRead(@RequestBody(required = false) ReadAllRequest req) {
        AppUser me = currentUser.require();
        Long teamId = req == null ? null : req.teamId();
        List<Notification> all = teamId == null
                ? notificationRepo.findByRecipientIdOrderByCreatedAtDesc(me.getId())
                : notificationRepo.findCurrentView(me.getId(), teamId);
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

    /** teamId = 订阅时的当前团队：SSE 增量按该视图口径带 unreadCount（裁定规则 2）。 */
    @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) Long teamId) {
        AppUser me = currentUser.require();
        return sseRegistry.subscribe(me.getId(), teamId);
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

    public record NotificationSettingsRequest(Map<String, Boolean> categories, Boolean compactNotification) {}

    /** P25：通知设置全量视图（分类开关 + 精简通知开关）。 */
    @GetMapping("/auth/notification-settings")
    public Map<String, Object> getSettings() {
        AppUser me = currentUser.require();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("categories", NotificationPrefs.of(me));
        out.put("compactNotification", NotificationPrefs.compactEnabled(me));
        return out;
    }

    @PatchMapping("/auth/notification-settings")
    @Transactional
    public Map<String, Object> patchSettings(@RequestBody NotificationSettingsRequest req) {
        AppUser me = currentUser.require();
        if (req.categories() != null) {
            me.setPreferencesJson(NotificationPrefs.merge(me, req.categories()));
        }
        if (req.compactNotification() != null) {
            me.setPreferencesJson(NotificationPrefs.mergeCompact(me, req.compactNotification()));
        }
        userRepo.save(me);
        return getSettings();
    }

    public record AppearanceSettingsRequest(String theme) {}

    /** 外观主题偏好（深色主题定稿）：{theme: system|light|dark|graphite}，存 preferences_json 根键 theme。 */
    @GetMapping("/auth/appearance-settings")
    public Map<String, Object> getAppearance() {
        return Map.of("theme", AppearancePrefs.of(currentUser.require()));
    }

    @PatchMapping("/auth/appearance-settings")
    @Transactional
    public Map<String, Object> patchAppearance(@RequestBody AppearanceSettingsRequest req) {
        AppUser me = currentUser.require();
        if (req.theme() != null) {
            me.setPreferencesJson(AppearancePrefs.merge(me, req.theme()));
            userRepo.save(me);
        }
        return getAppearance();
    }
}
