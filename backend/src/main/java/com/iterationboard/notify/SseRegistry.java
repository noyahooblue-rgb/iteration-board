package com.iterationboard.notify;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接注册表：按用户维护长连接，通知落库后实时推送。
 * 单机内存实现，与单体部署形态一致；连接断开自动清理。
 */
@Component
public class SseRegistry {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private final Map<Long, List<SseEmitter>> byUser = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = byUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"ok\":true}"));
        } catch (IOException ignored) {
            list.remove(emitter);
        }
        return emitter;
    }

    public void push(Long userId, String event, Object payload) {
        List<SseEmitter> list = byUser.get(userId);
        if (list == null || list.isEmpty()) return;
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(json));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }
}
