package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.client.RemoteUnavailable;
import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.exception.NotificationException;
import com.msa.notification.domain.notification.repository.SubscriptionRepository;
import com.msa.notification.domain.notification.service.WorkStore;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 페이지 경계와 기능 차단, 계정별 고정 식별자를 검증합니다. */
class ClaimCampaignFanoutTest {
    final Instant now = Instant.parse("2030-01-01T00:00:00Z");

    ClaimCampaignNotice event() {
        var campaign = UUID.randomUUID();
        return new ClaimCampaignNotice(
                UUID.nameUUIDFromBytes(
                        ("CLAIM_CAMPAIGN:" + campaign).getBytes(StandardCharsets.UTF_8)),
                1,
                "CLAIM_CAMPAIGN_NOTIFICATION",
                campaign,
                "WELCOME",
                "웰컴 쿠폰",
                now.plusSeconds(3600),
                now);
    }

    @Test
    void pagesDistinctAccountsAndKeepsIdsOnReplay() {
        var subscriptions = mock(SubscriptionRepository.class);
        var store = mock(WorkStore.class);
        var first =
                java.util.stream.IntStream.rangeClosed(1, 100)
                        .mapToObj(i -> new UUID(0, i))
                        .toList();
        when(subscriptions.claimAudience(isNull(), eq(now), any())).thenReturn(first);
        when(subscriptions.claimAudience(eq(first.getLast()), eq(now), any()))
                .thenReturn(List.of(new UUID(0, 101)));
        var fanout =
                new ClaimCampaignFanoutImpl(
                        subscriptions, store, Clock.fixed(now, ZoneOffset.UTC), true);
        var e = event();
        fanout.accept(e, e.campaignId().toString());
        fanout.accept(e, e.campaignId().toString());
        var events = ArgumentCaptor.forClass(BusinessNotice.class);
        verify(store, times(202)).acceptBusiness(events.capture());
        var all = events.getAllValues();
        assertThat(all.subList(0, 101)).isEqualTo(all.subList(101, 202));
        assertThat(all.stream().map(BusinessNotice::accountId).distinct().count()).isEqualTo(101);
    }

    @Test
    void disabledAndWrongPartitionNeverFanOut() {
        var subscriptions = mock(SubscriptionRepository.class);
        var store = mock(WorkStore.class);
        var fanout =
                new ClaimCampaignFanoutImpl(
                        subscriptions, store, Clock.fixed(now, ZoneOffset.UTC), false);
        var e = event();
        assertThatThrownBy(() -> fanout.accept(e, e.campaignId().toString()))
                .isInstanceOf(RemoteUnavailable.class);
        assertThatThrownBy(() -> fanout.accept(e, UUID.randomUUID().toString()))
                .isInstanceOf(NotificationException.class);
        verifyNoInteractions(subscriptions, store);
    }
}
