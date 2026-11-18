package com.iterationboard.common;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/** 控制器侧幂等辅助：命中已存结果时直接回放首次响应；否则执行后存档。 */
@Component
public class IdempotencyHelper {
    private final IdempotencyService service;
    private final ObjectMapper objectMapper;

    public IdempotencyHelper(IdempotencyService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Optional<ResponseEntity<String>> replay(Long userId, String scope, String key) {
        return service.find(userId, scope, key).map(st -> ResponseEntity.status(st.status())
                .contentType(MediaType.APPLICATION_JSON).body(st.body()));
    }

    public ResponseEntity<String> remember(Long userId, String scope, String key, int status, Object body) {
        String json = objectMapper.writeValueAsString(body);
        service.store(userId, scope, key, status, json);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
