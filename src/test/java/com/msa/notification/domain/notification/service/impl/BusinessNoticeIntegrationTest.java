package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.*;
import com.msa.notification.domain.notification.entity.*;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.repository.*;
import com.msa.notification.domain.notification.service.*;
import com.msa.notification.global.config.database.NotificationDatabaseSupport;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.time.*;
import java.util.*;

@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "notification.push.enabled=true",
            "notification.push.public-key=BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "notification.push.private-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE",
            "notification.push.subject=mailto:test@example.com",
            "notification.push.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        })
@AutoConfigureMockMvc
class BusinessNoticeIntegrationTest extends NotificationDatabaseSupport {
    @Autowired WorkStore store;
    @Autowired NotificationWorker worker;
    @Autowired NotificationService service;
    @Autowired WorkRepository work;
    @Autowired InboxRepository inbox;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired GuardRepository guards;
    @Autowired NoticeCursorRepository cursors;
    @MockitoBean EligibilityClient eligibility;
    @MockitoBean PushSender sender;
    @MockitoBean Clock clock;
    Instant now = Instant.parse("2030-01-01T00:00:00Z");
    UUID account, aggregate;

    @BeforeEach
    void setup() {
        work.deleteAll();
        inbox.deleteAll();
        subscriptions.deleteAll();
        guards.deleteAll();
        cursors.deleteAll();
        reset(eligibility, sender);
        when(clock.instant()).thenReturn(now);
        account = UUID.randomUUID();
        aggregate = UUID.randomUUID();
        when(eligibility.transactionalAllowed(any())).thenReturn(true);
        when(sender.send(any(), any(), anyInt(), anyString())).thenReturn(new PushResult(201, 0));
    }

    BusinessNotice event(String kind, long version, Instant occurred) {
        String identity = "ORDER:" + kind + ":" + aggregate + ":" + version + ":" + account;
        return BusinessNotice.builder()
                .eventId(
                        UUID.nameUUIDFromBytes(
                                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .schemaVersion(1)
                .type("BUSINESS_NOTIFICATION")
                .source("ORDER")
                .accountId(account)
                .notificationType(kind)
                .aggregateId(aggregate)
                .aggregateVersion(version)
                .targetType("ORDER")
                .targetId(aggregate)
                .occurredAt(occurred)
                .build();
    }

    @Test
    void sameEventStoresOneGeneralInboxWithoutPersonalizationConsent() {
        var event = event("ORDER_CONFIRMED", 1, now);
        store.acceptBusiness(event);
        store.acceptBusiness(event);
        worker.runNext();
        var messages = service.list(account, 0, 20).content();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().type()).isEqualTo("ORDER_CONFIRMED");
        assertThat(messages.getFirst().targetType()).isEqualTo("ORDER");
        assertThat(messages.getFirst().targetId()).isEqualTo(aggregate);
        verify(eligibility, never()).eligibleProductName(any());
    }

    @Test
    void sameIdWithChangedBodyIsRejectedWithoutOverwriting() {
        var original = event("ORDER_CONFIRMED", 1, now);
        store.acceptBusiness(original);
        var changed = event("ORDER_CONFIRMED", 1, now.plusSeconds(1));
        assertThatThrownBy(() -> store.acceptBusiness(changed))
                .isInstanceOf(NotificationException.class);
        worker.runNext();
        assertThat(inbox.count()).isEqualTo(1);
    }

    @Test
    void reverseOrderKeepsHistoryButOnlyPushesLatest() throws Exception {
        service.subscribe(account, request("reverse"));
        var newer = event("ORDER_DELIVERED", 2, now);
        var older = event("ORDER_SHIPPED", 1, now.minusSeconds(1));
        store.acceptBusiness(newer);
        store.acceptBusiness(older);
        for (int i = 0; i < 6; i++) worker.runNext();
        assertThat(inbox.count()).isEqualTo(2);
        verify(sender, times(1)).send(any(), any(), anyInt(), anyString());
        assertThat(work.findAll().stream().filter(w -> "NOTICE_PUSH".equals(w.getKind())).count())
                .isEqualTo(1);
    }

    @Test
    void queuedOldPushIsSkippedAfterNewVersionArrives() throws Exception {
        service.subscribe(account, request("late"));
        store.acceptBusiness(event("ORDER_SHIPPED", 1, now));
        worker.runNext();
        store.acceptBusiness(event("ORDER_DELIVERED", 2, now));
        for (int i = 0; i < 6; i++) worker.runNext();
        verify(sender, times(1)).send(any(), any(), anyInt(), anyString());
        assertThat(inbox.count()).isEqualTo(2);
    }

    @Test
    void oldEventStillCreatesInboxButDoesNotSendExpiredPush() throws Exception {
        service.subscribe(account, request("old"));
        store.acceptBusiness(event("ORDER_CONFIRMED", 1, now.minus(Duration.ofDays(8))));
        worker.runNext();
        assertThat(inbox.count()).isEqualTo(1);
        assertThat(work.count()).isEqualTo(1);
        verifyNoInteractions(sender);
    }

    @Test
    void inactiveRecipientIsSkippedButUnavailableRecipientCheckRetries() {
        var first = event("ORDER_CONFIRMED", 1, now);
        when(eligibility.transactionalAllowed(any())).thenReturn(false);
        store.acceptBusiness(first);
        worker.runNext();
        assertThat(work.findById(first.eventId()).orElseThrow().getStatus()).isEqualTo("SKIPPED");
        var second = event("ORDER_SHIPPED", 2, now);
        when(eligibility.transactionalAllowed(any())).thenThrow(new RemoteUnavailable());
        store.acceptBusiness(second);
        worker.runNext();
        assertThat(work.findById(second.eventId()).orElseThrow().getStatus()).isEqualTo("RETRY");
        assertThat(inbox.count()).isZero();
    }

    @Test
    void receiptAndPushEachRecheckLatestAccountState() throws Exception {
        service.subscribe(account, request("revoked"));
        store.acceptBusiness(event("ORDER_CONFIRMED", 1, now));
        worker.runNext();
        when(eligibility.transactionalAllowed(any())).thenReturn(false);
        worker.runNext();
        assertThat(inbox.count()).isEqualTo(1);
        verifyNoInteractions(sender);
    }

    @Test
    void rollbackDoesNotLeavePartialGeneralInbox() {
        store.acceptBusiness(event("ORDER_CONFIRMED", 1, now));
        var lease = store.claim().orElseThrow();
        when(clock.instant()).thenReturn(now.plusSeconds(121));
        store.readyBusiness(lease);
        assertThat(inbox.count()).isZero();
        assertThat(store.claim()).isPresent();
    }

    @Test
    void sourceAndPartitionMismatchAreRejected() {
        var consumer =
                new com.msa.notification.domain.notification.consumer.BusinessNoticeConsumer(
                        store, tools.jackson.databind.json.JsonMapper.builder().build());
        byte[] payload =
                tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsBytes(event("ORDER_CONFIRMED", 1, now));
        assertThatThrownBy(
                        () ->
                                consumer.consume(
                                        new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                                                "stockdrop.notifications.account.v1",
                                                0,
                                                0L,
                                                aggregate.toString(),
                                                payload)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(work.count()).isZero();
    }

    SubscriptionRequest request(String suffix) throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var pub = (ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] bytes = new byte[65];
        bytes[0] = 4;
        coordinate(pub.getW().getAffineX(), bytes, 1);
        coordinate(pub.getW().getAffineY(), bytes, 33);
        return new SubscriptionRequest(
                "https://web.push.apple.com/" + suffix,
                null,
                new SubscriptionRequest.Keys(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16])));
    }

    void coordinate(java.math.BigInteger value, byte[] target, int offset) {
        byte[] data = value.toByteArray();
        System.arraycopy(
                data,
                Math.max(0, data.length - 32),
                target,
                offset + Math.max(0, 32 - data.length),
                Math.min(32, data.length));
    }

    @Test
    void correctionDeadlineAndReferenceArePreservedForFrontend() {
        UUID related = UUID.randomUUID();
        String identity = "ACCOUNT:SELLER_CORRECTION_REQUESTED:" + aggregate + ":1:" + account;
        var event =
                BusinessNotice.builder()
                        .eventId(
                                UUID.nameUUIDFromBytes(
                                        identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .schemaVersion(1)
                        .type("BUSINESS_NOTIFICATION")
                        .source("ACCOUNT")
                        .accountId(account)
                        .notificationType("SELLER_CORRECTION_REQUESTED")
                        .aggregateId(aggregate)
                        .aggregateVersion(1)
                        .targetType("SELLER_APPLICATION")
                        .targetId(aggregate)
                        .relatedId(related)
                        .deadline(now.plusSeconds(3600))
                        .occurredAt(now)
                        .build();
        store.acceptBusiness(event);
        worker.runNext();
        var response = service.get(account, event.eventId());
        assertThat(response.relatedId()).isEqualTo(related);
        assertThat(response.deadline()).isEqualTo(event.deadline());
        assertThat(response.body()).contains("2030.01.01 10:00", "한국 시간");
    }
}
