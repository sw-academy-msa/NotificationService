package com.msa.notification.domain.notification.consumer;

import com.msa.notification.domain.notification.service.ProductAlertService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** 상품 공개 이벤트로 가격·재고·판매 일정 변화를 감지합니다. 상품 서비스에 별도 알림 이벤트를 요구하지 않습니다. */
@Component
@ConditionalOnProperty(name = "notification.product-alert-enabled", havingValue = "true")
public class ProductAlertConsumer {
    private final ProductAlertService service;
    private final ObjectMapper json;

    public ProductAlertConsumer(ProductAlertService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(topics = "stockdrop.product.public.v1", groupId = "notification-product-alert-v1")
    public void consume(ConsumerRecord<String, byte[]> record) {
        try {
            // 상품 공개 이벤트는 이미지·FAQ를 포함해 업무 알림보다 큽니다. 상품 서비스의 상한에 맞춥니다.
            if (record.value() == null || record.value().length > 4194304)
                throw new IllegalArgumentException();
            service.acceptProduct(record.key(), json.readTree(record.value()));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("PRODUCT_ALERT_INPUT_RETRY");
        }
    }
}
