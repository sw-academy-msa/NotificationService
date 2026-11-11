package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.*;
import com.msa.notification.domain.notification.service.*;
import com.msa.notification.global.config.push.PushProperties;

import org.slf4j.*;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

/** 외부 확인과 발송은 트랜잭션 밖입니다. 불확실한 실패는 영속 재시도하며 중복 표시 가능성을 숨기지 않습니다. */
@Service
public class NotificationWorkerImpl implements NotificationWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorkerImpl.class);
    private final WorkStore store;
    private final BusinessNoticePolicy business;
    private final EligibilityClient eligibility;
    private final PushSender sender;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PushProperties properties;

    public NotificationWorkerImpl(
            WorkStore store,
            EligibilityClient eligibility,
            PushSender sender,
            ObjectMapper mapper,
            Clock clock,
            PushProperties properties,
            BusinessNoticePolicy business) {
        this.business = business;
        this.store = store;
        this.eligibility = eligibility;
        this.sender = sender;
        this.mapper = mapper;
        this.clock = clock;
        this.properties = properties;
    }

    public void runNext() {
        store.claim().ifPresent(this::process);
    }

    private void process(Lease work) {
        try {
            if (!clock.instant().isBefore(work.expiresAt())) {
                store.finish(work, "SKIPPED", 0, 0);
                return;
            }
            if (work.kind().startsWith("NOTICE_")) {
                processBusiness(work);
                return;
            }
            Candidate event = mapper.readValue(work.payload(), Candidate.class);
            String name = eligibility.eligibleProductName(event);
            if (name == null) {
                store.finish(work, "SKIPPED", 0, 0);
                return;
            }
            if ("INTENT".equals(work.kind())) {
                store.ready(work, name);
                return;
            }
            if (!properties.enabled()) {
                store.finish(work, "RETRY", 0, 60);
                return;
            }
            var subscription = store.credentials(work);
            if (subscription.isEmpty()) {
                store.finish(work, "SKIPPED", 0, 0);
                return;
            }
            int ttl =
                    (int)
                            Math.min(
                                    300,
                                    Duration.between(clock.instant(), work.expiresAt())
                                            .getSeconds());
            if (ttl < 1) {
                store.finish(work, "SKIPPED", 0, 0);
                return;
            }
            byte[] payload =
                    mapper.writeValueAsBytes(
                            Map.of(
                                    "notificationId",
                                    event.eventId(),
                                    "title",
                                    "맞춤 상품 알림",
                                    "body",
                                    name.length() > 480 ? name.substring(0, 480) : name,
                                    "targetType",
                                    "PRODUCT",
                                    "targetId",
                                    event.productId()));
            String topic =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    java.nio.ByteBuffer.allocate(16)
                                            .putLong(event.eventId().getMostSignificantBits())
                                            .putLong(event.eventId().getLeastSignificantBits())
                                            .array());
            var result = sender.send(subscription.get(), payload, ttl, topic);
            int status = result.status();
            String state =
                    status >= 200 && status < 300
                            ? "SENT"
                            : status == 404 || status == 410
                                    ? "SKIPPED"
                                    : status == 401
                                                    || status == 403
                                                    || status == 429
                                                    || status >= 500
                                            ? "RETRY"
                                            : "FAILED";
            store.finish(work, state, status, result.retryAfterSeconds());
        } catch (RuntimeException failure) {
            // 원인 메시지, payload와 구독 URL은 남기지 않습니다.
            log.warn(
                    "notification work failed id={} attempt={} cause={}",
                    work.id(),
                    work.attempts(),
                    failure.getClass().getSimpleName());
            store.finish(work, "RETRY", 0, 0);
        }
    }

    /** 일반 업무 알림은 AI/상품 Query나 광고 동의를 의존하지 않습니다. 발송 직전에도 활성 상태를 재확인합니다. */
    private void processBusiness(Lease work) {
        BusinessNotice event = mapper.readValue(work.payload(), BusinessNotice.class);
        business.validate(event);
        if (!eligibility.transactionalAllowed(event.accountId())) {
            store.finish(work, "SKIPPED", 0, 0);
            return;
        }
        if ("NOTICE_INTENT".equals(work.kind())) {
            store.readyBusiness(work);
            return;
        }
        if (!store.currentBusiness(event)) {
            store.finish(work, "SKIPPED", 0, 0);
            return;
        }
        if (!properties.enabled()) {
            store.finish(work, "RETRY", 0, 60);
            return;
        }
        var subscription = store.credentials(work);
        if (subscription.isEmpty()) {
            store.finish(work, "SKIPPED", 0, 0);
            return;
        }
        int ttl =
                (int)
                        Math.min(
                                300,
                                Duration.between(clock.instant(), work.expiresAt()).getSeconds());
        if (ttl < 1) {
            store.finish(work, "SKIPPED", 0, 0);
            return;
        }
        var content = business.content(event);
        byte[] payload =
                mapper.writeValueAsBytes(
                        Map.of(
                                "notificationId",
                                event.eventId(),
                                "title",
                                content.title(),
                                "body",
                                content.body(),
                                "targetType",
                                event.targetType(),
                                "targetId",
                                event.targetId()));
        String topic =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                java.nio.ByteBuffer.allocate(16)
                                        .putLong(event.eventId().getMostSignificantBits())
                                        .putLong(event.eventId().getLeastSignificantBits())
                                        .array());
        var result = sender.send(subscription.get(), payload, ttl, topic);
        int status = result.status();
        String state =
                status >= 200 && status < 300
                        ? "SENT"
                        : status == 404 || status == 410
                                ? "SKIPPED"
                                : status == 401 || status == 403 || status == 429 || status >= 500
                                        ? "RETRY"
                                        : "FAILED";
        store.finish(work, state, status, result.retryAfterSeconds());
    }
}
