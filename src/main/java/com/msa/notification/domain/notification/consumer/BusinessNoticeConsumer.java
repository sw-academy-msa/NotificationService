package com.msa.notification.domain.notification.consumer;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.domain.notification.service.WorkStore;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/** 서비스별 전용 토픽과 source, 파티션 키를 대조합니다. 배송지 등 비공개 Query 토픽을 구독하지 않습니다. */
@Component
@ConditionalOnProperty(name = "notification.business-consumer-enabled", havingValue = "true")
public class BusinessNoticeConsumer {
    private final WorkStore store;
    private final ObjectMapper json;

    public BusinessNoticeConsumer(WorkStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @KafkaListener(
            topics = {
                "stockdrop.notifications.account.v1",
                "stockdrop.notifications.product.v1",
                "stockdrop.notifications.order.v1"
            },
            groupId = "notification-business-v1")
    public void consume(ConsumerRecord<String, byte[]> record) {
        try {
            if (record.value() == null || record.value().length > 16384)
                throw new IllegalArgumentException();
            var event = json.readValue(record.value(), BusinessNotice.class);
            String source =
                    Map.of(
                                    "stockdrop.notifications.account.v1",
                                    "ACCOUNT",
                                    "stockdrop.notifications.product.v1",
                                    "PRODUCT",
                                    "stockdrop.notifications.order.v1",
                                    "ORDER")
                            .get(record.topic());
            if (!java.util.Objects.equals(source, event.source())
                    || event.aggregateId() == null
                    || !event.aggregateId().toString().equals(record.key()))
                throw new IllegalArgumentException();
            store.acceptBusiness(event);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("BUSINESS_NOTIFICATION_INPUT_RETRY");
        }
    }
}
