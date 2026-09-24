package com.limingyuan.bookkeeping.idempotency.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Arrays;

@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(nullable = false, length = 64) private String scope;
    @Column(name = "operation_id", nullable = false, length = 64) private String operationId;
    @Column(name = "request_hash", nullable = false, columnDefinition = "BINARY(32)") private byte[] requestHash;
    @Column(name = "result_code", nullable = false, length = 32) private String resultCode;
    @Column(name = "resource_id") private Long resourceId;
    @Column(name = "created_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime createdAtUtc;

    protected IdempotencyRecord() { }

    public IdempotencyRecord(long userId, String scope, String operationId, byte[] requestHash, String resultCode,
            Long resourceId, LocalDateTime createdAtUtc) {
        this.userId = userId;
        this.scope = scope;
        this.operationId = operationId;
        this.requestHash = requestHash.clone();
        this.resultCode = resultCode;
        this.resourceId = resourceId;
        this.createdAtUtc = createdAtUtc;
    }

    public boolean sameRequest(byte[] hash) { return Arrays.equals(requestHash, hash); }
    public Long getResourceId() { return resourceId; }
    public String getResultCode() { return resultCode; }
}
