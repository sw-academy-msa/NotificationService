package com.msa.notification.domain.notification.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.*;
import java.util.UUID;

/** Kafka 입력과 푸시 작업을 영속 저장합니다. 네트워크 전후의 짧은 트랜잭션에서 lease를 확인합니다. */
@Entity
@Table(
        name = "delivery_work",
        schema = "notification",
        uniqueConstraints =
                @UniqueConstraint(columnNames = {"event_id", "kind", "subscription_key"}),
        // 1초 주기 작업이 대기 상태만 찾습니다. 완료된 작업이 쌓여도 전체를 읽지 않습니다.
        indexes = @Index(name = "idx_delivery_due", columnList = "status,next_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeliveryWork {
    @Id private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "subscription_key", nullable = false)
    private UUID subscriptionKey;

    private long generation;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant nextAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private UUID leaseToken;
    private Instant leaseUntil;
    private int attempts;
    private Integer httpStatus;

    @Builder
    public DeliveryWork(
            UUID id,
            UUID eventId,
            String kind,
            UUID subscriptionKey,
            long generation,
            String payload,
            Instant nextAt,
            Instant expiresAt) {
        this.id = id;
        this.eventId = eventId;
        this.kind = kind;
        this.subscriptionKey = subscriptionKey;
        this.generation = generation;
        this.payload = payload;
        this.nextAt = nextAt;
        this.expiresAt = expiresAt;
        this.status = "PENDING";
    }

    public void claim(Instant now) {
        leaseToken = UUID.randomUUID();
        leaseUntil = now.plusSeconds(120);
        attempts++;
    }

    public boolean owns(UUID token, Instant now) {
        return token != null
                && token.equals(leaseToken)
                && leaseUntil != null
                && now.isBefore(leaseUntil);
    }

    public void finish(String status, Integer httpStatus, Instant nextAt) {
        this.status = status;
        this.httpStatus = httpStatus;
        this.nextAt = nextAt;
        leaseToken = null;
        leaseUntil = null;
    }
}
