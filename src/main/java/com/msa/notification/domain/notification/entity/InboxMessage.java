package com.msa.notification.domain.notification.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** 알림함 원본입니다. 푸시 접수와 읽음 상태는 서로 독립적입니다. */
@Entity
@Table(
        name = "inbox_message",
        schema = "notification",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "account_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxMessage {
    @Id private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** 기존 product_id 컬럼명을 유지해 데이터 이전 없이 알림 대상 식별자로 일반화합니다. 상품 FK가 아닙니다. */
    @Column(name = "product_id", nullable = false)
    private UUID targetId;

    private String notificationType;
    private String targetType;
    private UUID relatedId;
    private Instant deadline;

    /** 지연 수신된 일반 알림도 원래 발생 시각을 표시할 수 있게 수신 시각과 구분합니다. */
    private Instant occurredAt;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant readAt;

    @Builder
    public InboxMessage(
            UUID id,
            UUID eventId,
            UUID accountId,
            UUID productId,
            UUID targetId,
            String notificationType,
            String targetType,
            UUID relatedId,
            Instant deadline,
            Instant occurredAt,
            String title,
            String body,
            Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.accountId = accountId;
        // 기존 맞춤 상품 빌더 호출과 저장된 행의 호환을 유지합니다.
        this.targetId = targetId == null ? productId : targetId;
        this.notificationType = notificationType;
        this.targetType = targetType;
        this.relatedId = relatedId;
        this.deadline = deadline;
        this.occurredAt = occurredAt;
        this.title = title;
        this.body = body;
        this.createdAt = createdAt;
    }
}
