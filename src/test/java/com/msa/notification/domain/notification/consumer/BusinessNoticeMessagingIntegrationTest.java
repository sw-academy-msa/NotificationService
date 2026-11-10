package com.msa.notification.domain.notification.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.client.EligibilityClient;
import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.domain.notification.repository.InboxRepository;
import com.msa.notification.global.config.database.NotificationDatabaseSupport;

import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 실제 세 도메인 Kafka 토픽을 자동 소비하고 동일 수신자의 알림함으로 멱등 반영합니다. */
@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "notification.business-consumer-enabled=true",
            "notification.worker-enabled=true",
            "notification.poll-ms=50"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusinessNoticeMessagingIntegrationTest extends NotificationDatabaseSupport {
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of("account", "product", "order").stream()
                                    .map(
                                            s ->
                                                    new NewTopic(
                                                            "stockdrop.notifications." + s + ".v1",
                                                            1,
                                                            (short) 1))
                                    .toList())
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

    @Test
    void duplicateDomainEventsProduceThreeDistinctInboxEntries() throws Exception {
        when(eligibility.transactionalAllowed(any())).thenReturn(true);
        UUID account = UUID.randomUUID();
        for (String source : List.of("ACCOUNT", "PRODUCT", "ORDER")) {
            UUID aggregate = UUID.randomUUID();
            String type =
                    switch (source) {
                        case "ACCOUNT" -> "SELLER_APPROVED";
                        case "PRODUCT" -> "PRODUCT_APPROVED";
                        default -> "ORDER_CONFIRMED";
                    };
            String target =
                    switch (source) {
                        case "ACCOUNT" -> "SELLER_APPLICATION";
                        case "PRODUCT" -> "SELLER_PRODUCT";
                        default -> "ORDER";
                    };
            UUID id =
                    UUID.nameUUIDFromBytes(
                            (source + ":" + type + ":" + aggregate + ":1:" + account)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var event =
                    BusinessNotice.builder()
                            .eventId(id)
                            .schemaVersion(1)
                            .type("BUSINESS_NOTIFICATION")
                            .source(source)
                            .accountId(account)
                            .notificationType(type)
                            .aggregateId(aggregate)
                            .aggregateVersion(1)
                            .targetType(target)
                            .targetId(aggregate)
                            .occurredAt(Instant.now())
                            .build();
            for (int i = 0; i < 2; i++)
                kafka.send(
                                "stockdrop.notifications." + source.toLowerCase() + ".v1",
                                aggregate.toString(),
                                json.writeValueAsBytes(event))
                        .get(10, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(inbox.countByAccountIdAndReadAtIsNull(account))
                                        .isEqualTo(3));
        assertThat(
                        inbox.findAll().stream()
                                .filter(r -> account.equals(r.getAccountId()))
                                .map(r -> r.getNotificationType())
                                .toList())
                .containsExactlyInAnyOrder(
                        "SELLER_APPROVED", "PRODUCT_APPROVED", "ORDER_CONFIRMED");
        verify(eligibility, never()).eligibleProductName(any());
    }
}
