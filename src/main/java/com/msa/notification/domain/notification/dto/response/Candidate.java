package com.msa.notification.domain.notification.dto.response;

import java.time.*;
import java.util.UUID;

/** Kafka 입력 계약입니다. HTTP 응답이 아니며 AI의 원본 eventId로 중복을 제거합니다. */
public record Candidate(
        UUID eventId,
        int schemaVersion,
        String type,
        UUID accountId,
        UUID analyticsId,
        UUID productId,
        String productName,
        String categoryCode,
        String categoryName,
        Instant publishedAt,
        Instant startsAt,
        Instant endsAt,
        LocalDate profileAsOf,
        Instant occurredAt) {}
