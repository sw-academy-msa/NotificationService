package com.msa.notification.domain.notification.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/** Kafka 전용 내부 DTO입니다. 화면 이동 식별자만 포함하며 심사 사유, 주소, 결제키는 넣지 않습니다. */
@Builder
public record BusinessNotice(
        UUID eventId,
        int schemaVersion,
        String type,
        String source,
        UUID accountId,
        String notificationType,
        UUID aggregateId,
        long aggregateVersion,
        String targetType,
        UUID targetId,
        UUID relatedId,
        Instant deadline,
        Instant occurredAt) {
    @Override
    public String toString() {
        return "BusinessNotice[eventId=" + eventId + "]";
    }

    public static class BusinessNoticeBuilder {
        @Override
        public String toString() {
            return "BusinessNoticeBuilder[REDACTED]";
        }
    }
}
