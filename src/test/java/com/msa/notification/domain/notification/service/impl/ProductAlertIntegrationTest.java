package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.exception.NotificationException;
import com.msa.notification.domain.notification.repository.*;
import com.msa.notification.domain.notification.service.*;
import com.msa.notification.global.config.database.NotificationDatabaseSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

/** 신청자 기준 판매 시작·가격 인하·수량 임박 알림 생성과 중복 방지, 설정에 따른 발송 제외를 검증합니다. */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
class ProductAlertIntegrationTest extends NotificationDatabaseSupport {
    @Autowired ProductAlertService alerts;
    @Autowired NotificationWorker worker;
    @Autowired NotificationService service;
    @Autowired WorkRepository work;
    @Autowired InboxRepository inbox;
    @Autowired NoticeCursorRepository cursors;
    @Autowired ProductAlertSubscriptionRepository subscriptions;
    @Autowired ProductAlertTargetRepository targets;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean EligibilityClient eligibility;
    @MockitoBean PushSender sender;
    @MockitoBean Clock clock;
    @MockitoBean JwtDecoder decoder;
    Instant now = Instant.parse("2030-01-01T00:00:00Z");
    Instant startsAt = now.plus(Duration.ofHours(1));
    UUID buyer, seller, product;

    @BeforeEach
    void setup() {
        work.deleteAll();
        inbox.deleteAll();
        cursors.deleteAll();
        subscriptions.deleteAll();
        targets.deleteAll();
        reset(eligibility, sender);
        when(clock.instant()).thenReturn(now);
        when(eligibility.saleAlertAllowed(any())).thenReturn(true);
        buyer = UUID.randomUUID();
        seller = UUID.randomUUID();
        product = UUID.randomUUID();
    }

    void publish(long version, long price, int initial, int reserved, int confirmed) {
        var event =
                Map.of(
                        "eventId", UUID.randomUUID(),
                        "schemaVersion", 1,
                        "type", "PRODUCT_UPSERT",
                        "aggregateId", product,
                        "aggregateVersion", version,
                        "occurredAt", now,
                        "product",
                                Map.of(
                                        "status", "APPROVED",
                                        "sellerId", seller,
                                        "name", "합성 상품",
                                        "price", price,
                                        "startsAt", startsAt,
                                        "endsAt", startsAt.plus(Duration.ofHours(3)),
                                        "options",
                                                List.of(
                                                        Map.of(
                                                                "initialQuantity", initial,
                                                                "reservedQuantity", reserved,
                                                                "confirmedQuantity", confirmed))));
        alerts.acceptProduct(product.toString(), json.valueToTree(event));
    }

    List<String> delivered() {
        for (int i = 0; i < 10; i++) worker.runNext();
        return service.list(buyer, 0, 20).content().stream().map(m -> m.type()).toList();
    }

    @Test
    void subscriberGetsPriceDropOnceAndNoAlertForPriceIncrease() {
        publish(1, 10000, 10, 0, 0);
        alerts.subscribe(buyer, product);
        publish(2, 12000, 10, 0, 0);
        assertThat(delivered()).isEmpty();
        publish(3, 9000, 10, 0, 0);
        publish(3, 9000, 10, 0, 0);
        publish(2, 1000, 10, 0, 0);
        assertThat(delivered()).containsExactly("PRICE_DROPPED");
        var message = service.list(buyer, 0, 20).content().getFirst();
        assertThat(message.targetType()).isEqualTo("PRODUCT");
        assertThat(message.targetId()).isEqualTo(product);
    }

    @Test
    void saleStartAlertIsCreatedTenMinutesBeforeStartOnlyOnce() {
        publish(1, 10000, 10, 0, 0);
        alerts.subscribe(buyer, product);
        assertThat(alerts.dispatchStarts()).isZero();
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(10)));
        assertThat(alerts.dispatchStarts()).isEqualTo(1);
        assertThat(alerts.dispatchStarts()).isZero();
        assertThat(delivered()).containsExactly("SALE_STARTING");
    }

    @Test
    void lowStockAlertIsSentOnceDuringSaleAtTwentyPercentRemaining() {
        publish(1, 10000, 10, 0, 0);
        alerts.subscribe(buyer, product);
        when(clock.instant()).thenReturn(startsAt.plusSeconds(60));
        publish(2, 10000, 10, 5, 2);
        assertThat(delivered()).isEmpty();
        publish(3, 10000, 10, 3, 5);
        // 남은 수량이 정확히 20%(2/10)일 때 알림이 나갑니다.
        assertThat(delivered()).containsExactly("STOCK_LOW");
        publish(4, 10000, 10, 1, 8);
        assertThat(delivered()).containsExactly("STOCK_LOW");
    }

    @Test
    void disabledSaleStartSettingKeepsAlertOutOfInbox() {
        publish(1, 10000, 10, 0, 0);
        alerts.subscribe(buyer, product);
        when(eligibility.saleAlertAllowed(buyer)).thenReturn(false);
        publish(2, 9000, 10, 0, 0);
        assertThat(delivered()).isEmpty();
        verify(eligibility, never()).transactionalAllowed(any());
    }

    @Test
    void subscriptionRejectsUnknownOwnAndEndedProductsAndCanBeCancelled() {
        assertThatThrownBy(() -> alerts.subscribe(buyer, product))
                .isInstanceOf(NotificationException.class);
        publish(1, 10000, 10, 0, 0);
        assertThatThrownBy(() -> alerts.subscribe(seller, product))
                .isInstanceOf(NotificationException.class);
        assertThat(alerts.subscribe(buyer, product).subscribed()).isTrue();
        assertThat(alerts.subscribe(buyer, product).subscribed()).isTrue();
        assertThat(alerts.list(buyer)).hasSize(1);
        alerts.unsubscribe(buyer, product);
        assertThat(alerts.status(buyer, product).subscribed()).isFalse();
        publish(2, 9000, 10, 0, 0);
        assertThat(delivered()).isEmpty();
        when(clock.instant()).thenReturn(startsAt.plus(Duration.ofHours(3)));
        assertThatThrownBy(() -> alerts.subscribe(buyer, product))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void endedProductsAreCleanedUpAfterOneDayButStateIsKeptLonger() {
        publish(2, 10000, 10, 0, 0);
        alerts.subscribe(buyer, product);
        when(clock.instant()).thenReturn(startsAt.plus(Duration.ofHours(27)).plusSeconds(1));
        assertThat(alerts.cleanup()).isEqualTo(1);
        assertThat(subscriptions.count()).isZero();
        // 상품 상태는 남아 있어 늦게 도착한 이전 버전이 판매를 되살리지 못합니다.
        assertThat(targets.count()).isEqualTo(1);
        publish(1, 10000, 10, 0, 0);
        assertThat(targets.findById(product).orElseThrow().getVersion()).isEqualTo(2);
        when(clock.instant()).thenReturn(startsAt.plus(Duration.ofHours(3)).plus(Duration.ofDays(30)).plusSeconds(1));
        alerts.cleanup();
        assertThat(targets.count()).isZero();
    }

    @Test
    void httpAllowsOnlyBuyersToSubscribe() throws Exception {
        publish(1, 10000, 10, 0, 0);
        // 실제 Bearer 요청으로 보내 보안 설정의 roles 클레임 변환까지 거치게 합니다.
        when(decoder.decode("seller-token")).thenReturn(token(seller, "SELLER"));
        when(decoder.decode("buyer-token")).thenReturn(token(buyer, "BUYER"));
        String path = "/api/v1/notifications/product-alerts/" + product;
        mvc.perform(put(path).header("Authorization", "Bearer seller-token"))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(true));
        mvc.perform(get(path).header("Authorization", "Bearer buyer-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(true));
    }

    Jwt token(UUID subject, String role) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(subject.toString())
                .claim("roles", List.of(role))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
    }
}
