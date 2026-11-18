package com.iterationboard.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 幂等记录：同用户 + 同端点范围 + 同 Idempotency-Key，10 分钟内重复提交返回首次结果。 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(nullable = false, length = 200)
    private String scope;
    @Column(name = "idem_key", nullable = false, length = 128)
    private String key;
    @Column(name = "response_status", nullable = false)
    private int responseStatus;
    @Column(name = "response_body", nullable = false)
    private String responseBody;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public IdempotencyRecord() {}
    public IdempotencyRecord(Long userId, String scope, String key, int responseStatus, String responseBody) {
        this.userId = userId; this.scope = scope; this.key = key;
        this.responseStatus = responseStatus; this.responseBody = responseBody;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getScope() { return scope; }
    public String getKey() { return key; }
    public int getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
}
