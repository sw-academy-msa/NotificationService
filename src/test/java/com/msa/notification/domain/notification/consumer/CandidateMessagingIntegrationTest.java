package com.msa.notification.domain.notification.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.client.EligibilityClient;
import com.msa.notification.domain.notification.dto.response.Candidate;
import com.msa.notification.domain.notification.repository.*;
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

/** 실제 Kafka 중복 입력과 자동 스케줄러를 통한 영속 알림함 생성을 검증합니다. */
@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "notification.consumer-enabled=true",
            "notification.worker-enabled=true",
            "notification.poll-ms=50"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateMessagingIntegrationTest extends NotificationDatabaseSupport {
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    static {
        KAFKA.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(
                            List.of(
                                    new NewTopic(
                                            "stockdrop.ai.recommendation-candidates.v1",
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
    @Autowired ObjectMapper json;
    @Autowired InboxRepository inbox;
    @Autowired WorkRepository work;
    @MockitoBean EligibilityClient eligibility;

    @Test
    void duplicateCandidateIsConsumedAndAutomaticallyProcessed() throws Exception {
        when(eligibility.eligibleProductName(any())).thenReturn("실제 Kafka 합성 상품");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        var event =
                new Candidate(
                        id,
                        1,
                        "PERSONALIZED_PRODUCT_CANDIDATE",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "상품",
                        "C",
                        "카테고리",
                        now,
                        now,
                        now.plusSeconds(600),
                        LocalDate.now(),
                        now);
        for (int i = 0; i < 2; i++)
            kafka.send(
                            "stockdrop.ai.recommendation-candidates.v1",
                            event.productId().toString(),
                            json.writeValueAsBytes(event))
                    .get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(25))
                .untilAsserted(
                        () -> {
                            assertThat(inbox.findById(id)).isPresent();
                            assertThat(work.findById(id).orElseThrow().getStatus())
                                    .isEqualTo("READY");
                        });
        assertThat(inbox.countByAccountIdAndReadAtIsNull(event.accountId())).isEqualTo(1);
    }
}
