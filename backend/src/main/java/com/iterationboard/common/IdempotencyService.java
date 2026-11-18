package com.iterationboard.common;

import com.iterationboard.domain.IdempotencyRecord;
import com.iterationboard.repo.IdempotencyRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等支持：创建类请求可带请求头 Idempotency-Key。
 * 同一用户、同一端点范围、同一键在 10 分钟内重复提交时，不再执行，直接返回首次结果。
 */
@Service
public class IdempotencyService {
    public static final Duration WINDOW = Duration.ofMinutes(10);
    private final IdempotencyRepo repo;

    public IdempotencyService(IdempotencyRepo repo) { this.repo = repo; }

    public record Stored(int status, String body) {}

    @Transactional(readOnly = true)
    public Optional<Stored> find(Long userId, String scope, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return repo.findByUserIdAndScopeAndKey(userId, scope, key)
                .filter(r -> r.getCreatedAt().isAfter(Instant.now().minus(WINDOW)))
                .map(r -> new Stored(r.getResponseStatus(), r.getResponseBody()));
    }

    @Transactional
    public void store(Long userId, String scope, String key, int status, String body) {
        if (key == null || key.isBlank()) return;
        repo.deleteByUserIdAndScopeAndKey(userId, scope, key);
        repo.flush();
        repo.save(new IdempotencyRecord(userId, scope, key, status, body));
    }
}
