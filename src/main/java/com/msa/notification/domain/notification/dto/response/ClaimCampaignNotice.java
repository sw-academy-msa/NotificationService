package com.msa.notification.domain.notification.dto.response;

import java.time.Instant;
import java.util.UUID;

/** 캠페인 광고 요청은 수신자 정보 없이 기존 캠페인 토픽으로 전달합니다. */
public record ClaimCampaignNotice(
        UUID eventId,
        int schemaVersion,
        String type,
        UUID campaignId,
        String campaignCode,
        String couponName,
        Instant deadline,
        Instant occurredAt) {
    @Override
    public String toString() {
        return "ClaimCampaignNotice[REDACTED]";
    }
}
