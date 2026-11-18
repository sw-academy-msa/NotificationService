package com.msa.notification.domain.notification.consumer;

import com.msa.notification.domain.notification.service.ProductAlertService;

import com.msa.notification.domain.notification.exception.NotificationException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** 상품 공개 이벤트로 가격·재고·판매 일정 변화를 감지합니다. 상품 서비스에 별도 알림 이벤트를 요구하지 않습니다. */
@Component
@ConditionalOnProperty(name = "notification.product-alert-enabled", havingValue = "true")
public class ProductAlertConsumer {
    private static final Logger log = LoggerFactory.getLogger(ProductAlertConsumer.class);
    private final ProductAlertService service;
    private final ObjectMapper json;

    public ProductAlertConsumer(ProductAlertService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @KafkaListener(topics = "stockdrop.product.public.v1", groupId = "notification-product-alert-v1")
    public void consume(ConsumerRecord<String, byte[]> record) {
        // 상품 공개 이벤트는 이미지·FAQ를 포함해 업무 알림보다 큽니다. 상품 서비스의 상한에 맞춥니다.
        // 크기 초과·JSON 오류·형식 오류는 다시 읽어도 같으므로 건너뜁니다. 막아 두면 같은 파티션의 다음 상품이 처리되지 않습니다.
        if (record.value() == null || record.value().length > 4194304) {
            skip(record, "size");
            return;
        }
        tools.jackson.databind.JsonNode event;
        try {
            event = json.readTree(record.value());
        } catch (RuntimeException invalid) {
            skip(record, "json");
            return;
        }
        try {
            service.acceptProduct(record.key(), event);
        } catch (NotificationException invalid) {
            skip(record, "format");
        } catch (RuntimeException failure) {
            // DB 장애 같은 일시 오류는 오프셋을 넘기지 않고 다시 시도합니다.
            throw new IllegalStateException("PRODUCT_ALERT_INPUT_RETRY");
        }
    }

    // 상품 내용·키 원문은 남기지 않고 위치와 사유만 남깁니다.
    private void skip(ConsumerRecord<String, byte[]> record, String reason) {
        log.warn(
                "product alert input skipped partition={} offset={} reason={}",
                record.partition(),
                record.offset(),
                reason);
    }
}
