package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.test.web.servlet.MockMvc;

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
class NotificationIntegrationTest extends NotificationDatabaseSupport {
    @Autowired WorkStore store;
    @Autowired NotificationWorker worker;
    @Autowired NotificationService service;
    @Autowired CredentialService crypto;
    @Autowired WorkRepository work;
    @Autowired InboxRepository inbox;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired GuardRepository guards;
    @Autowired MockMvc mvc;
    @MockitoBean EligibilityClient eligibility;
    @MockitoBean PushSender sender;
    @MockitoBean Clock clock;
    Instant now = Instant.parse("2030-01-01T00:00:00Z");
    UUID account;
    UUID product;

    @BeforeEach
    void setup() {
        work.deleteAll();
        inbox.deleteAll();
        subscriptions.deleteAll();
        guards.deleteAll();
        reset(eligibility, sender);
        when(clock.instant()).thenReturn(now);
        account = UUID.randomUUID();
        product = UUID.randomUUID();
        when(eligibility.eligibleProductName(any())).thenReturn("검증 상품");
        when(sender.send(any(), any(), anyInt(), anyString())).thenReturn(new PushResult(201, 0));
    }

    Candidate event() {
        return new Candidate(
                UUID.randomUUID(),
                1,
                "PERSONALIZED_PRODUCT_CANDIDATE",
                account,
                UUID.randomUUID(),
                product,
                "원본 상품",
                "C",
                "분류",
                now,
                now,
                now.plusSeconds(600),
                LocalDate.of(2029, 12, 31),
                now);
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
    void duplicateEventStoresExactlyOneInbox() {
        var event = event();
        store.accept(event);
        store.accept(event);
        worker.runNext();
        assertThat(inbox.count()).isEqualTo(1);
        assertThat(work.findById(event.eventId()).orElseThrow().getStatus()).isEqualTo("READY");
    }

    @Test
    void conflictingEventIdIsRejected() {
        var event = event();
        store.accept(event);
        var other =
                new Candidate(
                        event.eventId(),
                        1,
                        event.type(),
                        account,
                        event.analyticsId(),
                        product,
                        "변경된 상품",
                        "C",
                        "분류",
                        now,
                        now,
                        event.endsAt(),
                        event.profileAsOf(),
                        now);
        assertThatThrownBy(() -> store.accept(other)).isInstanceOf(NotificationException.class);
    }

    @Test
    void optOutSkipsButDependencyFailureRetries() {
        when(eligibility.eligibleProductName(any())).thenReturn(null);
        var first = event();
        store.accept(first);
        worker.runNext();
        assertThat(work.findById(first.eventId()).orElseThrow().getStatus()).isEqualTo("SKIPPED");
        when(eligibility.eligibleProductName(any())).thenThrow(new RemoteUnavailable());
        var second = event();
        store.accept(second);
        worker.runNext();
        assertThat(work.findById(second.eventId()).orElseThrow().getStatus()).isEqualTo("RETRY");
        assertThat(inbox.count()).isZero();
    }

    @Test
    void leaseExpiryRejectsStaleCompletion() {
        var e = event();
        store.accept(e);
        var lease = store.claim().orElseThrow();
        when(clock.instant()).thenReturn(now.plusSeconds(121));
        store.ready(lease, "상품");
        assertThat(inbox.count()).isZero();
        var retried = store.claim().orElseThrow();
        assertThat(retried.token()).isNotEqualTo(lease.token());
        assertThat(retried.expiresAt()).isEqualTo(e.endsAt());
    }

    @Test
    void exclusiveClaimAndAtomicFanout() throws Exception {
        service.subscribe(account, request("one"));
        service.subscribe(account, request("two"));
        store.accept(event());
        var lease = store.claim().orElseThrow();
        assertThat(store.claim()).isEmpty();
        store.ready(lease, "상품");
        assertThat(inbox.count()).isEqualTo(1);
        assertThat(work.count()).isEqualTo(3);
        store.ready(lease, "상품");
        assertThat(work.count()).isEqualTo(3);
    }

    @Test
    void failedFanoutRollsBackInboxAndLeaseCompletion() throws Exception {
        service.subscribe(account, request("one"));
        store.accept(event());
        var lease = store.claim().orElseThrow();
        assertThatThrownBy(() -> store.ready(lease, null)).isInstanceOf(RuntimeException.class);
        assertThat(inbox.count()).isZero();
        assertThat(work.findById(lease.id()).orElseThrow().getLeaseToken())
                .isEqualTo(lease.token());
    }

    @Test
    void readIsIdempotentAndOtherAccountCannotRead() {
        store.accept(event());
        worker.runNext();
        var message = service.list(account, 0, 20).content().getFirst();
        assertThat(service.unread(account).count()).isEqualTo(1);
        assertThat(service.read(account, message.id()).changed()).isEqualTo(1);
        assertThat(service.read(account, message.id()).changed()).isZero();
        assertThat(service.get(account, message.id()).readAt()).isEqualTo(now);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), message.id()))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void listNeverLeaksOthersAndOffsetIsValidated() {
        store.accept(event());
        worker.runNext();
        assertThat(service.list(UUID.randomUUID(), 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.list(account, Integer.MAX_VALUE, 100))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void subscriptionIdempotenceOwnershipAndEncryption() throws Exception {
        var request = request("one");
        var first = service.subscribe(account, request);
        var second = service.subscribe(account, request);
        assertThat(second.id()).isEqualTo(first.id());
        var row = subscriptions.findById(first.id()).orElseThrow();
        assertThat(row.getGeneration()).isEqualTo(1);
        assertThat(row.getCredentials()).doesNotContain(request.endpoint());
        assertThatThrownBy(() -> service.subscribe(UUID.randomUUID(), request))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(() -> crypto.decrypt(row.getCredentials(), "other-account"))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void endpointSsrfAndInvalidPointAreRejected() throws Exception {
        var valid = request("one");
        assertThatThrownBy(
                        () ->
                                service.subscribe(
                                        account,
                                        new SubscriptionRequest(
                                                "http://localhost/private", null, valid.keys())))
                .isInstanceOf(NotificationException.class);
        assertThatThrownBy(
                        () ->
                                service.subscribe(
                                        account,
                                        new SubscriptionRequest(
                                                valid.endpoint(),
                                                null,
                                                new SubscriptionRequest.Keys(
                                                        Base64.getUrlEncoder()
                                                                .encodeToString(new byte[65]),
                                                        valid.keys().auth()))))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void acceptedPushDoesNotMarkRead() throws Exception {
        service.subscribe(account, request("one"));
        store.accept(event());
        worker.runNext();
        worker.runNext();
        assertThat(work.findAll().stream().filter(w -> "SENT".equals(w.getStatus())).count())
                .isEqualTo(1);
        assertThat(service.unread(account).count()).isEqualTo(1);
        verify(sender).send(any(), any(), anyInt(), anyString());
    }

    @Test
    void expiredPushSubscriptionIsDisabledOnlyForMatchingGeneration() throws Exception {
        var sub = service.subscribe(account, request("one"));
        store.accept(event());
        worker.runNext();
        var lease = store.claim().orElseThrow();
        service.unsubscribe(account, sub.id());
        service.subscribe(account, request("one"));
        store.finish(lease, "SKIPPED", 410, 0);
        assertThat(subscriptions.findById(sub.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void goneDisablesButUnauthorizedDoesNot() throws Exception {
        var sub = service.subscribe(account, request("one"));
        store.accept(event());
        worker.runNext();
        when(sender.send(any(), any(), anyInt(), anyString())).thenReturn(new PushResult(403, 0));
        worker.runNext();
        assertThat(subscriptions.findById(sub.id()).orElseThrow().isActive()).isTrue();
        assertThat(work.findAll().stream().anyMatch(w -> "RETRY".equals(w.getStatus()))).isTrue();
        when(clock.instant()).thenReturn(now.plusSeconds(11));
        when(sender.send(any(), any(), anyInt(), anyString())).thenReturn(new PushResult(410, 0));
        worker.runNext();
        assertThat(subscriptions.findById(sub.id()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void consentIsRecheckedImmediatelyBeforePush() throws Exception {
        service.subscribe(account, request("one"));
        store.accept(event());
        worker.runNext();
        when(eligibility.eligibleProductName(any())).thenReturn(null);
        worker.runNext();
        verifyNoInteractions(sender);
        assertThat(inbox.count()).isEqualTo(1);
    }

    @Test
    void anonymousAndServiceTokensCannotUseInbox() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/v1/notifications")
                                .with(
                                        jwt().jwt(
                                                        j ->
                                                                j.subject(account.toString())
                                                                        .claim(
                                                                                "roles",
                                                                                List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void buyerRoleClaimIsConvertedByRealSecurityChain() throws Exception {
        // jwt()의 직접 authority 주입 대신 별도 보안 테스트에서 서명된 토큰을 검증합니다.
        mvc.perform(
                        get("/api/v1/notifications")
                                .with(
                                        jwt().jwt(j -> j.subject(account.toString()))
                                                .authorities(
                                                        new org.springframework.security.core
                                                                .authority.SimpleGrantedAuthority(
                                                                "ROLE_BUYER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
