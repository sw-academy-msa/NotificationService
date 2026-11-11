package com.msa.notification.domain.notification.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.*;

/** 공개 DTO만 반환하며 구독 비밀과 JPA 엔티티는 포함하지 않습니다. */
public final class NotificationResponses {
    private NotificationResponses() {}

    @Builder
    public record Message(
            UUID id,
            String type,
            String title,
            String body,
            String targetType,
            UUID targetId,
            UUID relatedId,
            Instant deadline,
            Instant occurredAt,
            Instant createdAt,
            Instant readAt) {}

    @Builder
    public record Messages(List<Message> content, int page, int size, long totalElements) {}

    @Builder
    public record Count(long count) {}

    @Builder
    public record Subscription(UUID id, boolean active, Instant expiresAt, Instant createdAt) {}

    @Builder
    public record Configuration(boolean enabled, String publicKey) {}

    @Builder
    public record Changed(int changed) {}

    /** 내부 작업 반환 DTO이며 HTTP 응답에 노출하지 않습니다. */
    public record Lease(
            UUID id,
            UUID token,
            String kind,
            UUID subscriptionId,
            long generation,
            String payload,
            Instant expiresAt,
            int attempts) {}

    /** 내부 전송 결과이며 HTTP 응답에 노출하지 않습니다. */
    public record PushResult(int status, long retryAfterSeconds) {}
}
