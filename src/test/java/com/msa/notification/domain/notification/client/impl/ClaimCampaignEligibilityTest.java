package com.msa.notification.domain.notification.client.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.global.config.client.ClientProperties;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/** 받기형은 Account 광고 동의만 조회하며 분석 참여나 상품 상태를 요구하지 않습니다. */
class ClaimCampaignEligibilityTest {
    @Test
    void usesDedicatedConsentPurposeWithoutCallingAiOrProduct() {
        var http = mock(InternalHttp.class);
        var tokens = mock(ServiceTokenProvider.class);
        when(tokens.token()).thenReturn("synthetic");
        when(http.send(anyString(), anyString(), anyString(), anyMap(), isNull()))
                .thenReturn(
                        new com.msa.notification.domain.notification.dto.response.InternalReply(
                                200, "{\"success\":true,\"data\":{\"eligible\":true}}"));
        var p =
                new ClientProperties(
                        "http", "account-service", "ai-service", "product-query-service", "", "");
        var client =
                new EligibilityClientImpl(
                        http, p, tokens, JsonMapper.builder().build(), Clock.systemUTC());
        var account = UUID.randomUUID();
        var event =
                BusinessNotice.builder()
                        .accountId(account)
                        .source("CAMPAIGN")
                        .notificationType("CAMPAIGN_CLAIM_AVAILABLE")
                        .couponName("웰컴 쿠폰")
                        .build();
        assertThat(client.campaignContent(event).title()).isEqualTo("(광고) 쿠폰이 도착했어요");
        verify(http)
                .send(
                        eq("account-service"),
                        eq("GET"),
                        eq(
                                "/internal/v1/notification-eligibility?accountId="
                                        + account
                                        + "&purpose=CLAIM_CAMPAIGN"),
                        anyMap(),
                        isNull());
        verifyNoMoreInteractions(http);
    }
}
