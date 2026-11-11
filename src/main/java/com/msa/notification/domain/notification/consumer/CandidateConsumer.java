package com.msa.notification.domain.notification.consumer;

import com.msa.notification.domain.notification.dto.response.Candidate;
import com.msa.notification.domain.notification.service.WorkStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** listener는 입력 저장만 수행합니다. commit 전 장애나 재수신은 원본 eventId로 멱등 처리합니다. */
@Component
@ConditionalOnProperty(name = "notification.consumer-enabled", havingValue = "true")
public class CandidateConsumer {
    private final WorkStore store;
    private final ObjectMapper mapper;

    public CandidateConsumer(WorkStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "stockdrop.ai.recommendation-candidates.v1",
            groupId = "notification-personalized-products-v1")
    public void consume(byte[] body) {
        try {
            if (body == null || body.length > 16384) throw new IllegalArgumentException();
            store.accept(mapper.readValue(body, Candidate.class));
        } catch (RuntimeException failure) {
            // 파싱 예외가 원본 이벤트를 Kafka 프레임워크 로그에 포함하지 않게 원인을 노출하지 않습니다.
            throw new IllegalStateException("NOTIFICATION_INPUT_RETRY");
        }
    }
}
