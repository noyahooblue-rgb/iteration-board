package com.iterationboard.notify;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * SSE 连接注册表：按用户维护长连接，通知落库后实时推送。
 * 单机内存实现，与单体部署形态一致；连接断开自动清理。
 *
 * <p>P2c 裁定（zu5skyzg）：连接订阅时带上当前团队（viewTeamId），推送增量按连接的
 * 当前视图口径生成（同一用户开在不同团队的两个标签页，各自收到与自己视图一致的
 * unreadCount）。未带团队的连接按「全部未读」口径生成。
 */
@Component
public class SseRegistry {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private record Conn(SseEmitter emitter, Long viewTeamId) { }
    private final Map<Long, List<Conn>> byUser = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEmitter subscribe(Long userId) {
        return subscribe(userId, null);
    }

    public SseEmitter subscribe(Long userId, Long viewTeamId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Conn conn = new Conn(emitter, viewTeamId);
        List<Conn> list = byUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(conn);
        Runnable cleanup = () -> list.remove(conn);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"ok\":true}"));
        } catch (IOException ignored) {
            list.remove(conn);
        }
        return emitter;
    }

    public void push(Long userId, String event, Object payload) {
        push(userId, event, viewTeamId -> payload);
    }

    /** 按连接的当前视图团队分别生成 payload（同一 viewTeamId 只生成一次）。 */
    public void push(Long userId, String event, Function<Long, Object> payloadForViewTeam) {
        List<Conn> list = byUser.get(userId);
        if (list == null || list.isEmpty()) return;
        Map<Long, String> jsonByTeam = new HashMap<>();
        for (Conn conn : list) {
            String json = jsonByTeam.computeIfAbsent(conn.viewTeamId(), team -> {
                try {
                    return mapper.writeValueAsString(payloadForViewTeam.apply(team));
                } catch (Exception e) {
                    return null;
                }
            });
            if (json == null) continue;
            try {
                conn.emitter().send(SseEmitter.event().name(event).data(json));
            } catch (Exception e) {
                list.remove(conn);
            }
        }
    }
}
