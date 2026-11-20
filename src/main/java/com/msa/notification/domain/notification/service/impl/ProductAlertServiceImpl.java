package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.ProductAlert;
import com.msa.notification.domain.notification.entity.*;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.repository.*;
import com.msa.notification.domain.notification.service.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * 판매 시작 알림 신청자를 관심 상품 대상자로 사용합니다. 알림은 기존 업무 알림 경로(알림함·푸시)로 보내며, 상품 버전과 알림 종류로 ID를 정해 같은 알림을
 * 한 번만 만듭니다.
 */
@Service
@Transactional
public class ProductAlertServiceImpl implements ProductAlertService {
    static final Duration START_LEAD = Duration.ofMinutes(10);
    static final int MAX_SUBSCRIPTIONS = 200;
    static final int START_BATCH = 50;
    static final Duration SUBSCRIPTION_RETENTION = Duration.ofDays(1);
    static final Duration TARGET_RETENTION = Duration.ofDays(30);

    private final ProductAlertSubscriptionRepository subscriptions;
    private final ProductAlertTargetRepository targets;
    private final WorkStore store;
    private final WorkRepository work;
    private final Clock clock;

    public ProductAlertServiceImpl(
            ProductAlertSubscriptionRepository subscriptions,
            ProductAlertTargetRepository targets,
            WorkStore store,
            WorkRepository work,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.targets = targets;
        this.store = store;
        this.work = work;
        this.clock = clock;
    }

    public ProductAlert subscribe(UUID account, UUID product) {
        // 배치와 같은 상품 잠금을 먼저 잡아 신청 직전의 완료 표시 때문에 신청자가 빠지지 않게 합니다.
        var target =
                targets.lock(product)
                        .filter(t -> t.getVersion() >= 0)
                        .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOT_FOUND));
        // 판매자 본인 상품과 이미 끝난 판매는 신청하지 않습니다.
        Instant now = clock.instant();
        if (target.getSellerId().equals(account) || !now.isBefore(target.getEndsAt()))
            throw new NotificationException(NotificationErrorCode.INVALID_REQUEST);
        UUID id = ProductAlertSubscription.key(account, product);
        // 서로 다른 상품을 동시에 신청해도 한도를 넘지 않게 계정 단위로 순서대로 처리합니다.
        subscriptions.lockAccount(account);
        boolean alreadySubscribed = subscriptions.existsById(id);
        if (!alreadySubscribed
                && subscriptions.countByAccountId(account) >= MAX_SUBSCRIPTIONS)
            throw new NotificationException(NotificationErrorCode.CONFLICT);
        subscriptions.insert(id, account, product, now);
        // 10분 전 배치가 이미 지나갔어도 시작 전 창 안의 신청자는 즉시 같은 알림 경로로 보냅니다.
        if (!alreadySubscribed && !now.isBefore(target.getStartsAt().minus(START_LEAD))
                && now.isBefore(target.getStartsAt())) {
            if (!target.isStartNoticed()) {
                // 아직 배치가 처리하지 않았다면 기존 신청자까지 함께 처리하고 완료 표시를 원자 저장합니다.
                target.markStartNoticed();
                notify(target, "SALE_STARTING", target.getStartsAt());
            } else {
                notifySubscriber(target, "SALE_STARTING", target.getStartsAt(), account);
            }
        }
        return status(account, product);
    }

    public void unsubscribe(UUID account, UUID product) {
        subscriptions.deleteById(ProductAlertSubscription.key(account, product));
    }

    @Transactional(readOnly = true)
    public ProductAlert status(UUID account, UUID product) {
        return subscriptions
                .findById(ProductAlertSubscription.key(account, product))
                .map(this::response)
                .orElseGet(() -> ProductAlert.builder().productId(product).subscribed(false).build());
    }

    @Transactional(readOnly = true)
    public List<ProductAlert> list(UUID account) {
        return subscriptions.findByAccountIdOrderByCreatedAtDesc(account).stream()
                .map(this::response)
                .toList();
    }

    public void acceptProduct(String key, JsonNode event) {
        // 같은 토픽의 문의 이벤트는 상품 알림과 관계없습니다.
        if (!"PRODUCT_UPSERT".equals(event.path("type").asText())) return;
        var product = event.path("product");
        UUID id = uuid(event.path("aggregateId"));
        if (id == null
                || !id.toString().equals(key)
                || !event.path("aggregateVersion").canConvertToLong()
                || !product.isObject()) throw invalid();
        long version = event.path("aggregateVersion").asLong();
        UUID seller = uuid(product.path("sellerId"));
        Instant startsAt = instant(product.path("startsAt"));
        Instant endsAt = instant(product.path("endsAt"));
        if (seller == null
                || startsAt == null
                || endsAt == null
                || !product.path("price").canConvertToLong()) throw invalid();
        long price = product.path("price").asLong();
        Instant now = clock.instant();
        targets.insertIfAbsent(id, seller, now);
        var target = targets.lock(id).orElseThrow();
        // 늦게 도착한 이전 버전은 무시합니다.
        if (version <= target.getVersion()) return;
        boolean known = target.getVersion() >= 0;
        long previousPrice = target.getPrice();
        target.apply(version, price, startsAt, endsAt, now);
        if (known && price < previousPrice && now.isBefore(startsAt))
            notify(target, "PRICE_DROPPED", endsAt);
        if (!target.isLowStockNoticed()
                && !now.isBefore(startsAt)
                && now.isBefore(endsAt)
                && lowStock(product.path("options"))) {
            target.markLowStockNoticed();
            notify(target, "STOCK_LOW", endsAt);
        }
    }

    public int dispatchStarts() {
        Instant now = clock.instant();
        var due = targets.dueStarts(now.plus(START_LEAD), now, START_BATCH);
        for (var target : due) {
            target.markStartNoticed();
            notify(target, "SALE_STARTING", target.getStartsAt());
        }
        return due.size();
    }

    public int cleanup() {
        Instant now = clock.instant();
        // 신청은 판매 종료 하루 뒤 지웁니다. 상품 상태는 Kafka 보관 기간(7일)보다 길게 남겨 늦은 이전 버전을 계속 거절합니다.
        int removed = subscriptions.deleteEnded(now.minus(SUBSCRIPTION_RETENTION));
        targets.deleteEnded(now.minus(TARGET_RETENTION));
        return removed;
    }

    /** 남은 수량(최초 - 확보 - 확정)이 최초 수량의 20% 이하이고 품절 전일 때만 임박으로 봅니다. */
    private boolean lowStock(JsonNode options) {
        if (!options.isArray() || options.isEmpty()) return false;
        long initial = 0, remaining = 0;
        for (var option : options) {
            long total = option.path("initialQuantity").asLong();
            initial += total;
            remaining +=
                    Math.max(
                            0,
                            total
                                    - option.path("reservedQuantity").asLong()
                                    - option.path("confirmedQuantity").asLong());
        }
        return initial > 0 && remaining > 0 && remaining * 5 <= initial;
    }

    private void notify(ProductAlertTarget target, String type, Instant deadline) {
        for (var subscription : subscriptions.findByProductId(target.getProductId())) {
            notifySubscriber(target, type, deadline, subscription.getAccountId());
        }
    }

    /** 상품 잠금 아래에서 기존 의도 유무를 확인하여 반복 신청과 취소 후 재신청도 중복 없이 처리합니다. */
    private void notifySubscriber(ProductAlertTarget target, String type, Instant deadline, UUID account) {
        String identity =
                "PRODUCT_ALERT:"
                        + type
                        + ":"
                        + target.getProductId()
                        + ":"
                        + target.getVersion()
                        + ":"
                        + account;
        UUID eventId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        // 기존 알림은 발생 시각까지 불변입니다. 같은 ID를 새 시각의 payload로 다시 저장하면 충돌합니다.
        if (work.existsById(eventId)) return;
        store.acceptBusiness(
                BusinessNotice.builder()
                        .eventId(eventId)
                        .schemaVersion(1)
                        .type("BUSINESS_NOTIFICATION")
                        .source("PRODUCT_ALERT")
                        .accountId(account)
                        .notificationType(type)
                        .aggregateId(target.getProductId())
                        .aggregateVersion(target.getVersion())
                        .targetType("PRODUCT")
                        .targetId(target.getProductId())
                        .deadline(deadline)
                        .occurredAt(clock.instant())
                        .build());
    }

    private ProductAlert response(ProductAlertSubscription s) {
        return ProductAlert.builder()
                .productId(s.getProductId())
                .subscribed(true)
                .createdAt(s.getCreatedAt())
                .build();
    }

    private UUID uuid(JsonNode value) {
        try {
            return value.isString() ? UUID.fromString(value.asText()) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ISO 문자열이 기본이며, 숫자 타임스탬프 설정으로 직렬화된 초 단위 값도 받습니다.
    private Instant instant(JsonNode value) {
        try {
            if (value.isString()) return Instant.parse(value.asText());
            if (value.isNumber()) {
                var seconds = value.decimalValue();
                return Instant.ofEpochSecond(
                        seconds.longValue(),
                        seconds.remainder(java.math.BigDecimal.ONE).movePointRight(9).intValue());
            }
            return null;
        } catch (DateTimeException | ArithmeticException ex) {
            return null;
        }
    }

    private NotificationException invalid() {
        return new NotificationException(NotificationErrorCode.INVALID_REQUEST);
    }
}
