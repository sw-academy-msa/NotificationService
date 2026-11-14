package com.msa.notification.domain.notification.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** capability URL과 키는 AES-GCM으로 암호화합니다. 갱신 세대로 늦은 410 응답을 구분합니다. */
@Entity
@Table(
        name = "push_subscription",
        schema = "notification",
        // 알림 발송마다 계정의 활성 구독을 찾습니다.
        indexes = @Index(name = "idx_push_subscription_account", columnList = "account_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushSubscription {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false, unique = true, length = 64)
    private String endpointHash;

    @Column(columnDefinition = "text")
    private String credentials;

    private boolean active;
    private long generation;
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Builder
    public PushSubscription(UUID id, UUID accountId, String endpointHash, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.endpointHash = endpointHash;
        this.createdAt = createdAt;
    }

    public void activate(String credentials, Instant expiresAt) {
        this.credentials = credentials;
        this.expiresAt = expiresAt;
        this.active = true;
        this.generation++;
    }

    public void deactivate() {
        this.active = false;
        this.credentials = null;
        this.generation++;
    }
}
