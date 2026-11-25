package com.msa.notification.domain.notification.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.client.EligibilityClient;
import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.domain.notification.repository.InboxRepository;
import com.msa.notification.global.config.database.NotificationDatabaseSupport;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 실제 Kafka에서 회원제 알림을 알림함에 멱등 반영하고 계약 오류는 즉시 DLT로 격리합니다. */
@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "notification.coupon-consumer-enabled=true",
            "notification.worker-enabled=true",
            "notification.campaign.mode=DEMO",
            "notification.campaign.demo-account-ids=11111111-1111-1111-1111-111111111111",
            "notification.poll-ms=50"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponNoticeMessagingIntegrationTest extends NotificationDatabaseSupport {
    static final String TOPIC = "stockdrop.notifications.coupon.v1";
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(TOPIC, 1, (short) 1),
                                    new NewTopic(TOPIC + ".DLT", 1, (short) 1),
                                    new NewTopic(
                                            "stockdrop.notifications.campaign.v1", 1, (short) 1),
                                    new NewTopic(
                                            "stockdrop.notifications.campaign.v1.DLT",
                                            1,
                                            (short) 1)))
                    .all()
                    .get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry p) {
        p.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        p.add(
                "spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        p.add(
                "spring.kafka.producer.value-serializer",
                () -> "org.apache.kafka.common.serialization.ByteArraySerializer");
    }

    @Autowired KafkaTemplate<String, byte[]> kafka;
    @Autowired InboxRepository inbox;
    @Autowired ObjectMapper json;
    @MockitoBean EligibilityClient eligibility;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.msa.notification.domain.notification.service.WorkStore store;

    private BusinessNotice notice(UUID account, String type, String target) {
        UUID aggregate = UUID.randomUUID();
        String identity = "COUPON:" + type + ":" + aggregate + ":1:" + account;
        return BusinessNotice.builder()
                .eventId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
                .schemaVersion(1)
                .type("BUSINESS_NOTIFICATION")
                .source("COUPON")
                .accountId(account)
                .notificationType(type)
                .aggregateId(aggregate)
                .aggregateVersion(1)
                .targetType(target)
                .targetId(UUID.randomUUID())
                .deadline(Instant.now().plusSeconds(600))
                .occurredAt(Instant.now())
                .build();
    }

    @Test
    void membershipNoticesReachInboxOnceAndInvalidGoesToDlt() throws Exception {
        when(eligibility.campaignContent(any()))
                .thenAnswer(
                        invocation ->
                                com.msa.notification.domain.notification.service.impl
                                        .CouponNoticeText.content(
                                        invocation.getArgument(0), "상품", "분류", 2));
        UUID account = UUID.fromString("11111111-1111-1111-1111-111111111111");
        for (String type : List.of("COUPON_EXPIRING", "COUPON_MEMBER_ARRIVED")) {
            var event = notice(account, type, "COUPON");
            for (int i = 0; i < 2; i++)
                kafka.send(TOPIC, event.accountId().toString(), json.writeValueAsBytes(event))
                        .get(10, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(inbox.countByAccountIdAndReadAtIsNull(account))
                                        .isEqualTo(2));
        var invalid = notice(account, "COUPON_EXPIRING", "ORDER");
        kafka.send(TOPIC, invalid.accountId().toString(), json.writeValueAsBytes(invalid))
                .get(10, TimeUnit.SECONDS);
        try (var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                "bootstrap.servers",
                                KAFKA.getBootstrapServers(),
                                "group.id",
                                "membership-notice-dlt-" + UUID.randomUUID(),
                                "auto.offset.reset",
                                "earliest"),
                        new StringDeserializer(),
                        new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(25))
                    .until(
                            () -> {
                                for (var record : consumer.poll(Duration.ofMillis(300)))
                                    if (invalid.eventId()
                                            .toString()
                                            .equals(
                                                    json.readTree(record.value())
                                                            .path("eventId")
                                                            .asText())) return true;
                                return false;
                            });
        }
        assertThat(inbox.countByAccountIdAndReadAtIsNull(account)).isEqualTo(2);
    }

    @Test
    void temporaryDatabaseFailureRetriesThreeTimesThenDlt() throws Exception {
        var event = notice(UUID.randomUUID(), "COUPON_EXPIRING", "COUPON");
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic"))
                .when(store)
                .acceptBusiness(event);
        kafka.send(TOPIC, event.accountId().toString(), json.writeValueAsBytes(event))
                .get(10, TimeUnit.SECONDS);
        try (var consumer =
                new KafkaConsumer<String, byte[]>(
                        Map.of(
                                "bootstrap.servers",
                                KAFKA.getBootstrapServers(),
                                "group.id",
                                "retry-dlt-" + UUID.randomUUID(),
                                "auto.offset.reset",
                                "earliest"),
                        new StringDeserializer(),
                        new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(TOPIC + ".DLT"));
            await().atMost(Duration.ofSeconds(30))
                    .until(
                            () -> {
                                for (var record : consumer.poll(Duration.ofMillis(300)))
                                    if (event.eventId()
                                            .toString()
                                            .equals(
                                                    json.readTree(record.value())
                                                            .path("eventId")
                                                            .asText())) return true;
                                return false;
                            });
        }
        verify(store, times(4)).acceptBusiness(event);
    }
}
