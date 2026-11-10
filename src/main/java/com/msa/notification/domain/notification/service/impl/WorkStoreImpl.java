package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.*;
import com.msa.notification.domain.notification.entity.*;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.repository.*;
import com.msa.notification.domain.notification.service.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

/** inbox와 모든 기기 작업은 한 트랜잭션으로 생성합니다. 재시도는 원래 만료를 연장하지 않습니다. */
@Service
@Transactional
public class WorkStoreImpl implements WorkStore {
    private final WorkRepository work;
    private final InboxRepository inbox;
    private final SubscriptionRepository subscriptions;
    private final CredentialService crypto;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final NoticeCursorRepository cursors;
    private final BusinessNoticePolicy policy;

    public WorkStoreImpl(
            WorkRepository work,
            InboxRepository inbox,
            SubscriptionRepository subscriptions,
            CredentialService crypto,
            ObjectMapper mapper,
            Clock clock,
            NoticeCursorRepository cursors,
            BusinessNoticePolicy policy) {
        this.cursors = cursors;
        this.policy = policy;
        this.work = work;
        this.inbox = inbox;
        this.subscriptions = subscriptions;
        this.crypto = crypto;
        this.mapper = mapper;
        this.clock = clock;
    }

    public void accept(Candidate event) {
        if (event == null
                || event.eventId() == null
                || event.schemaVersion() != 1
                || !"PERSONALIZED_PRODUCT_CANDIDATE".equals(event.type())
                || event.accountId() == null
                || event.productId() == null
                || event.analyticsId() == null
                || event.endsAt() == null
                || event.productName() == null
                || event.productName().length() > 500)
            throw new NotificationException(NotificationErrorCode.INVALID_REQUEST);
        String payload = mapper.writeValueAsString(event);
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384)
            throw new NotificationException(NotificationErrorCode.INVALID_REQUEST);
        work.insertIntent(event.eventId(), payload, clock.instant(), event.endsAt());
        var prior = work.findById(event.eventId()).orElseThrow();
        if (!prior.getPayload().equals(payload))
            throw new NotificationException(NotificationErrorCode.CONFLICT);
    }

    public void acceptBusiness(BusinessNotice event) {
        policy.validate(event);
        String payload = mapper.writeValueAsString(event);
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384)
            throw new NotificationException(NotificationErrorCode.INVALID_REQUEST);
        // 알림함 저장은 전송 유효 기간 때문에 유실시키지 않습니다. 푸시의 재시도 기한은 별도로 계산합니다.
        work.insertNotice(
                event.eventId(), payload, clock.instant(), Instant.parse("9999-12-31T23:59:59Z"));
        var row = work.findById(event.eventId()).orElseThrow();
        if (!"NOTICE_INTENT".equals(row.getKind()) || !payload.equals(row.getPayload()))
            throw new NotificationException(NotificationErrorCode.CONFLICT);
        cursors.advance(policy.cursor(event), event.aggregateVersion());
    }

    public boolean currentBusiness(BusinessNotice event) {
        return cursors.findById(policy.cursor(event))
                .map(c -> c.getLatestVersion() == event.aggregateVersion())
                .orElse(false);
    }

    public void readyBusiness(Lease lease) {
        var row = owned(lease);
        if (row == null) return;
        var event = mapper.readValue(row.getPayload(), BusinessNotice.class);
        var content = policy.content(event);
        if (!inbox.existsByEventId(event.eventId()))
            inbox.save(
                    InboxMessage.builder()
                            .id(event.eventId())
                            .eventId(event.eventId())
                            .accountId(event.accountId())
                            .targetId(event.targetId())
                            .notificationType(event.notificationType())
                            .targetType(event.targetType())
                            .relatedId(event.relatedId())
                            .deadline(event.deadline())
                            .occurredAt(event.occurredAt())
                            .title(content.title())
                            .body(content.body())
                            .createdAt(clock.instant())
                            .build());
        Instant expiry = policy.pushDeadline(event);
        if (currentBusiness(event) && clock.instant().isBefore(expiry))
            for (var subscription : subscriptions.findByAccountIdAndActiveTrue(event.accountId())) {
                if (subscription.getExpiresAt() != null
                        && !clock.instant().isBefore(subscription.getExpiresAt())) continue;
                UUID id =
                        UUID.nameUUIDFromBytes(
                                (event.eventId() + ":" + subscription.getId())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!work.existsById(id))
                    work.save(
                            DeliveryWork.builder()
                                    .id(id)
                                    .eventId(event.eventId())
                                    .kind("NOTICE_PUSH")
                                    .subscriptionKey(subscription.getId())
                                    .generation(subscription.getGeneration())
                                    .payload(row.getPayload())
                                    .nextAt(clock.instant())
                                    .expiresAt(expiry)
                                    .build());
            }
        row.finish("READY", null, clock.instant());
    }

    public Optional<Lease> claim() {
        return work.next(clock.instant())
                .map(
                        row -> {
                            row.claim(clock.instant());
                            return new Lease(
                                    row.getId(),
                                    row.getLeaseToken(),
                                    row.getKind(),
                                    row.getSubscriptionKey(),
                                    row.getGeneration(),
                                    row.getPayload(),
                                    row.getExpiresAt(),
                                    row.getAttempts());
                        });
    }

    public void ready(Lease lease, String productName) {
        var row = owned(lease);
        if (row == null) return;
        if (!clock.instant().isBefore(row.getExpiresAt())) {
            row.finish("SKIPPED", null, clock.instant());
            return;
        }
        Candidate event = mapper.readValue(row.getPayload(), Candidate.class);
        if (!inbox.existsByEventId(event.eventId()))
            inbox.save(
                    InboxMessage.builder()
                            .id(event.eventId())
                            .eventId(event.eventId())
                            .accountId(event.accountId())
                            .productId(event.productId())
                            .title("맞춤 상품 알림")
                            .body(
                                    productName.length() > 480
                                            ? productName.substring(0, 480)
                                            : productName)
                            .createdAt(clock.instant())
                            .build());
        for (var subscription : subscriptions.findByAccountIdAndActiveTrue(event.accountId())) {
            if (subscription.getExpiresAt() != null
                    && !clock.instant().isBefore(subscription.getExpiresAt())) continue;
            UUID id =
                    UUID.nameUUIDFromBytes(
                            (event.eventId() + ":" + subscription.getId())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!work.existsById(id))
                work.save(
                        DeliveryWork.builder()
                                .id(id)
                                .eventId(event.eventId())
                                .kind("PUSH")
                                .subscriptionKey(subscription.getId())
                                .generation(subscription.getGeneration())
                                .payload(row.getPayload())
                                .nextAt(clock.instant())
                                .expiresAt(row.getExpiresAt())
                                .build());
        }
        row.finish("READY", null, clock.instant());
    }

    public Optional<SubscriptionRequest> credentials(Lease lease) {
        if (owned(lease) == null) return Optional.empty();
        return subscriptions
                .findById(lease.subscriptionId())
                .filter(
                        s ->
                                s.isActive()
                                        && s.getGeneration() == lease.generation()
                                        && (s.getExpiresAt() == null
                                                || clock.instant().isBefore(s.getExpiresAt())))
                .map(s -> crypto.decrypt(s.getCredentials(), s.getAccountId() + ":" + s.getId()));
    }

    public void finish(Lease lease, String state, int httpStatus, long retryAfterSeconds) {
        var row = owned(lease);
        if (row == null) return;
        // 410이 늦게 도착하더라도 갱신된 구독을 폐기하지 않습니다.
        if (httpStatus == 404 || httpStatus == 410)
            subscriptions
                    .lockById(lease.subscriptionId())
                    .filter(s -> s.getGeneration() == lease.generation() && s.isActive())
                    .ifPresent(PushSubscription::deactivate);
        long delay =
                Math.min(
                        3600,
                        Math.max(retryAfterSeconds, 10L << Math.min(lease.attempts() - 1, 8)));
        if (!clock.instant().isBefore(row.getExpiresAt())) state = "SKIPPED";
        row.finish(state, httpStatus == 0 ? null : httpStatus, clock.instant().plusSeconds(delay));
    }

    private DeliveryWork owned(Lease lease) {
        return work.lock(lease.id())
                .filter(w -> w.owns(lease.token(), clock.instant()))
                .orElse(null);
    }
}
