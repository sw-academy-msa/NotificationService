package com.msa.notification.domain.notification.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.dto.response.ClaimCampaignNotice;
import com.msa.notification.domain.notification.exception.NotificationException;
import com.msa.notification.domain.notification.service.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** 기존 캠페인 토픽에서 방송 계약만 별도 처리하고 잘못된 JSON은 계약 오류로 격리합니다. */
class ClaimCampaignConsumerTest {
    @Test
    void routesNewContractToFanoutAndRejectsMalformedJson() {
        var store = mock(WorkStore.class);
        var fanout = mock(ClaimCampaignFanout.class);
        var json = JsonMapper.builder().build();
        var consumer = new CouponNoticeConsumer(store, json, fanout);
        var id = UUID.randomUUID();
        var event =
                new ClaimCampaignNotice(
                        UUID.randomUUID(),
                        1,
                        "CLAIM_CAMPAIGN_NOTIFICATION",
                        id,
                        "WELCOME",
                        "웰컴 쿠폰",
                        Instant.parse("2030-01-02T00:00:00Z"),
                        Instant.parse("2030-01-01T00:00:00Z"));
        consumer.campaign(
                new ConsumerRecord<>(
                        "stockdrop.notifications.campaign.v1",
                        0,
                        1L,
                        id.toString(),
                        json.writeValueAsBytes(event)));
        verify(fanout).accept(event, id.toString());
        verifyNoInteractions(store);
        assertThatThrownBy(
                        () ->
                                consumer.campaign(
                                        new ConsumerRecord<>(
                                                "stockdrop.notifications.campaign.v1",
                                                0,
                                                2L,
                                                id.toString(),
                                                new byte[] {'{'})))
                .isInstanceOf(NotificationException.class);
    }
}
