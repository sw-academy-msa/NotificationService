package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.repository.SubscriptionRepository;
import com.msa.notification.domain.notification.service.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

/** 페이지별 계정 열거와 고정 이벤트 ID로 부분 실패 뒤 재소비해도 중복 발송하지 않습니다. */
@Service
public class ClaimCampaignFanoutImpl implements ClaimCampaignFanout {
    private final SubscriptionRepository subscriptions;
    private final WorkStore store;
    private final Clock clock;
    private final boolean enabled;

    public ClaimCampaignFanoutImpl(
            SubscriptionRepository subscriptions,
            WorkStore store,
            Clock clock,
            @Value("${notification.claim-notification-enabled:false}") boolean enabled) {
        this.subscriptions = subscriptions;
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
    }

    public void accept(ClaimCampaignNotice e, String key) {
        if (e == null
                || e.campaignId() == null
                || !e.campaignId().toString().equals(key)
                || e.schemaVersion() != 1
                || !"CLAIM_CAMPAIGN_NOTIFICATION".equals(e.type())
                || e.eventId() == null
                || !e.eventId().equals(id("CLAIM_CAMPAIGN:" + e.campaignId()))
                || e.campaignCode() == null
                || !e.campaignCode().matches("[A-Z0-9_]{1,40}")
                || e.couponName() == null
                || e.couponName().isBlank()
                || e.couponName().length() > 60
                || e.deadline() == null
                || e.occurredAt() == null
                || !e.occurredAt().isBefore(e.deadline()))
            throw new NotificationException(NotificationErrorCode.INVALID_REQUEST);
        // 비활성 시 성공 처리하지 않아 운영자가 재소비 또는 DLT 복구할 수 있습니다.
        if (!enabled) throw new com.msa.notification.domain.notification.client.RemoteUnavailable();
        if (!clock.instant().isBefore(e.deadline())) return;
        UUID after = null;
        while (true) {
            var accounts =
                    subscriptions.claimAudience(after, clock.instant(), PageRequest.of(0, 100));
            for (UUID account : accounts) {
                store.acceptBusiness(
                        BusinessNotice.builder()
                                .eventId(
                                        id(
                                                "CAMPAIGN:CAMPAIGN_CLAIM_AVAILABLE:"
                                                        + e.campaignId()
                                                        + ":0:"
                                                        + account))
                                .schemaVersion(1)
                                .type("BUSINESS_NOTIFICATION")
                                .source("CAMPAIGN")
                                .accountId(account)
                                .notificationType("CAMPAIGN_CLAIM_AVAILABLE")
                                .aggregateId(e.campaignId())
                                .aggregateVersion(0)
                                .targetType("CLAIM_CAMPAIGN")
                                .targetId(e.campaignId())
                                .deadline(e.deadline())
                                .occurredAt(e.occurredAt())
                                .campaignCode(e.campaignCode())
                                .couponName(e.couponName())
                                .build());
            }
            if (accounts.size() < 100) return;
            after = accounts.getLast();
        }
    }

    private UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
