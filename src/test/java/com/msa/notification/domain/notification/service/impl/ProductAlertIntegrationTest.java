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
    // 구독자가 없던 10분 전 배치가 상품을 처리한 뒤 신청한 구매자도 즉시 알림함에 도달해야 합니다.
    void subscriberInsideWindowAfterEmptyDispatchStillGetsStartNoticeOnce() {
        publish(1, 10000, 10, 0, 0);
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(10)));
        assertThat(alerts.dispatchStarts()).isEqualTo(1);
        assertThat(delivered()).isEmpty();
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(9)));
        alerts.subscribe(buyer, product);
        assertThat(delivered()).containsExactly("SALE_STARTING");
        alerts.subscribe(buyer, product);
        alerts.unsubscribe(buyer, product);
        alerts.subscribe(buyer, product);
        assertThat(alerts.dispatchStarts()).isZero();
        assertThat(delivered()).containsExactly("SALE_STARTING");
    }

    @Test
    // 앞선 신청자 발송 이후 새 구매자의 신청을 추가해도 상품 단위 완료 표시 때문에 누락하지 않습니다.
    void newBuyerAfterEarlierSubscribersWereNotifiedGetsTheirOwnNotice() {
        publish(1, 10000, 10, 0, 0);
        UUID earlier = UUID.randomUUID();
        alerts.subscribe(earlier, product);
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(10)));
        alerts.dispatchStarts();
        when(clock.instant()).thenReturn(startsAt.minusSeconds(20));
        alerts.subscribe(buyer, product);
        assertThat(delivered()).containsExactly("SALE_STARTING");
        assertThat(service.list(earlier, 0, 20).content()).hasSize(1);
    }

    @Test
    // 시작 직전 신청과 뒤따르는 배치가 같은 이벤트를 다른 발생 시각으로 중복 저장하지 않아야 합니다.
    void subscriptionWithinWindowBeforePollAndRepeatedRequestsAreIdempotent() {
        publish(1, 10000, 10, 0, 0);
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(5)));
        alerts.subscribe(buyer, product);
        assertThat(work.count()).isEqualTo(1);
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(4)));
        alerts.dispatchStarts();
        alerts.subscribe(buyer, product);
        assertThat(delivered()).containsExactly("SALE_STARTING");
    }

    @Test
    // 서로 다른 구매자의 동시 신청과 배치도 동일 상품 잠금 순서를 따라 누락 및 교착 없이 한 번씩 처리합니다.
    void concurrentSubscriptionsAndPollEachCreateOnlyOneStartNotice() throws Exception {
        publish(1, 10000, 10, 0, 0);
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(5)));
        UUID anotherBuyer = UUID.randomUUID();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                return alerts.subscribe(buyer, product);
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                return alerts.subscribe(anotherBuyer, product);
                            });
            var poll =
                    pool.submit(
                            () -> {
                                start.await();
                                return alerts.dispatchStarts();
                            });
            start.countDown();
            first.get(20, java.util.concurrent.TimeUnit.SECONDS);
            second.get(20, java.util.concurrent.TimeUnit.SECONDS);
            poll.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            // 잠금 대기 회귀로 시간이 넘으면 남은 작업을 끊어 테스트가 멈추지 않게 합니다.
            pool.shutdownNow();
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(delivered()).containsExactly("SALE_STARTING");
        assertThat(service.list(anotherBuyer, 0, 20).content()).hasSize(1);
    }

    @Test
    // 알림함 저장 전에 취소해 건너뛴 시작 알림은 같은 창 안에서 다시 신청하면 다시 대기시켜 한 번만 보냅니다.
    void cancelBeforeDeliveryThenResubscribeStillGetsStartNotice() {
        publish(1, 10000, 10, 0, 0);
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(5)));
        alerts.subscribe(buyer, product);
        alerts.unsubscribe(buyer, product);
        assertThat(delivered()).isEmpty();
        alerts.subscribe(buyer, product);
        assertThat(delivered()).containsExactly("SALE_STARTING");
        alerts.unsubscribe(buyer, product);
        alerts.subscribe(buyer, product);
        assertThat(delivered()).containsExactly("SALE_STARTING");
    }

    @Test
    // 판매가 시작된 뒤 신청하면 지난 시작 알림을 만들지 않고 다른 상품 알림 구독만 유지합니다.
    void subscriptionAfterStartCreatesNoStartNotice() {
        publish(1, 10000, 10, 0, 0);
        when(clock.instant()).thenReturn(startsAt.plusSeconds(60));
        assertThat(alerts.subscribe(buyer, product).subscribed()).isTrue();
        assertThat(alerts.dispatchStarts()).isZero();
        assertThat(work.count()).isZero();
        assertThat(delivered()).isEmpty();
    }

    @Test
    // 시작 15분 전 신청은 바로 만들지 않고 10분 전 배치에서 한 번만 만듭니다.
    void subscriptionBeforeWindowWaitsForDispatch() {
        publish(1, 10000, 10, 0, 0);
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(15)));
        alerts.subscribe(buyer, product);
        assertThat(work.count()).isZero();
        when(clock.instant()).thenReturn(startsAt.minus(Duration.ofMinutes(10)));
        assertThat(alerts.dispatchStarts()).isEqualTo(1);
        alerts.subscribe(buyer, product);
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
