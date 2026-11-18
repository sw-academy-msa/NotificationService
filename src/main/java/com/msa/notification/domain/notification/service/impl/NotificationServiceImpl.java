package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.*;
import com.msa.notification.domain.notification.entity.*;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.repository.*;
import com.msa.notification.domain.notification.service.*;
import com.msa.notification.global.config.push.PushProperties;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** API의 로컬 트랜잭션만 담당합니다. Account DB를 직접 조회하거나 푸시 네트워크를 호출하지 않습니다. */
@Service
@Transactional(readOnly = true)
public class NotificationServiceImpl implements NotificationService {
    private final InboxRepository inbox;
    private final SubscriptionRepository subscriptions;
    private final GuardRepository guards;
    private final CredentialService credentials;
    private final PushProperties properties;
    private final Clock clock;

    public NotificationServiceImpl(
            InboxRepository inbox,
            SubscriptionRepository subscriptions,
            GuardRepository guards,
            CredentialService credentials,
            PushProperties properties,
            Clock clock) {
        this.inbox = inbox;
        this.subscriptions = subscriptions;
        this.guards = guards;
        this.credentials = credentials;
        this.properties = properties;
        this.clock = clock;
    }

    public Messages list(UUID account, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE)
            throw error(NotificationErrorCode.INVALID_REQUEST);
        var rows =
                inbox.findByAccountIdOrderByCreatedAtDescIdDesc(
                        account, PageRequest.of(page, size));
        return Messages.builder()
                .content(rows.map(this::response).toList())
                .page(page)
                .size(size)
                .totalElements(rows.getTotalElements())
                .build();
    }

    public Message get(UUID account, UUID id) {
        return response(own(account, id));
    }

    public Count unread(UUID account) {
        return Count.builder().count(inbox.countByAccountIdAndReadAtIsNull(account)).build();
    }

    @Transactional
    public Changed read(UUID account, UUID id) {
        own(account, id);
        return Changed.builder().changed(inbox.read(id, account, clock.instant())).build();
    }

    @Transactional
    public Changed readAll(UUID account) {
        return Changed.builder().changed(inbox.readAll(account, clock.instant())).build();
    }

    @Transactional
    public Subscription subscribe(UUID account, SubscriptionRequest request) {
        if (!properties.enabled()) throw error(NotificationErrorCode.UNAVAILABLE);
        String hash = credentials.validateAndHash(request);
        Instant expiry =
                request.expirationTime() == null
                        ? null
                        : Instant.ofEpochMilli(request.expirationTime());
        if (expiry != null && !expiry.isAfter(clock.instant()))
            throw error(NotificationErrorCode.INVALID_REQUEST);
        // 계정별 직렬화로 여러 브라우저의 동시 구독 한도 초과를 막습니다.
        guards.ensure(account);
        guards.lock(account);
        var existing = subscriptions.lockByHash(hash);
        if (existing.isPresent() && !existing.get().getAccountId().equals(account))
            throw error(NotificationErrorCode.FORBIDDEN);
        var row =
                existing.orElseGet(
                        () ->
                                PushSubscription.builder()
                                        .id(UUID.randomUUID())
                                        .accountId(account)
                                        .endpointHash(hash)
                                        .createdAt(clock.instant())
                                        .build());
        if (row.isActive()) {
            var old = credentials.decrypt(row.getCredentials(), aad(row));
            if (old.equals(request)) return response(row);
        } else if (subscriptions.findByAccountIdAndActiveTrue(account).size() >= 20)
            throw error(NotificationErrorCode.INVALID_REQUEST);
        row.activate(credentials.encrypt(request, aad(row)), expiry);
        return response(subscriptions.saveAndFlush(row));
    }

    public List<Subscription> subscriptions(UUID account) {
        return subscriptions.findByAccountIdOrderByCreatedAtDesc(account).stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public void unsubscribe(UUID account, UUID id) {
        guards.ensure(account);
        guards.lock(account);
        var row =
                subscriptions
                        .lockById(id)
                        .orElseThrow(() -> error(NotificationErrorCode.NOT_FOUND));
        if (!row.getAccountId().equals(account)) throw error(NotificationErrorCode.FORBIDDEN);
        if (row.isActive()) row.deactivate();
    }

    public Configuration configuration() {
        return Configuration.builder()
                .enabled(properties.enabled())
                .publicKey(properties.enabled() ? properties.publicKey() : null)
                .build();
    }

    private InboxMessage own(UUID account, UUID id) {
        var row = inbox.findById(id).orElseThrow(() -> error(NotificationErrorCode.NOT_FOUND));
        if (!row.getAccountId().equals(account)) throw error(NotificationErrorCode.FORBIDDEN);
        return row;
    }

    private Message response(InboxMessage row) {
        return Message.builder()
                .id(row.getId())
                .type(
                        row.getNotificationType() == null
                                ? "PERSONALIZED_PRODUCT"
                                : row.getNotificationType())
                .title(row.getTitle())
                .body(row.getBody())
                .targetType(row.getTargetType() == null ? "PRODUCT" : row.getTargetType())
                .targetId(row.getTargetId())
                .relatedId(row.getRelatedId())
                .deadline(row.getDeadline())
                .occurredAt(row.getOccurredAt() == null ? row.getCreatedAt() : row.getOccurredAt())
                .createdAt(row.getCreatedAt())
                .readAt(row.getReadAt())
                .build();
    }

    private Subscription response(PushSubscription row) {
        return Subscription.builder()
                .id(row.getId())
                .active(
                        row.isActive()
                                && (row.getExpiresAt() == null
                                        || clock.instant().isBefore(row.getExpiresAt())))
                .expiresAt(row.getExpiresAt())
                .createdAt(row.getCreatedAt())
                .build();
    }

    private String aad(PushSubscription row) {
        return row.getAccountId() + ":" + row.getId();
    }

    private NotificationException error(NotificationErrorCode code) {
        return new NotificationException(code);
    }
}
