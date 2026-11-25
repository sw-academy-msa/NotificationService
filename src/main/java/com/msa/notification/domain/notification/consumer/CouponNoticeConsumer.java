package com.msa.notification.domain.notification.consumer;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.service.WorkStore;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** 쿠폰과 캠페인 입력은 전용 재시도 경로와 기존 업무 알림 저장소를 사용합니다. */
@Component
@ConditionalOnProperty(name = "notification.coupon-consumer-enabled", havingValue = "true")
public class CouponNoticeConsumer {
    private final WorkStore store;
    private final ObjectMapper json;

    public CouponNoticeConsumer(WorkStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @KafkaListener(
            id = "coupon-notices",
            topics = "stockdrop.notifications.coupon.v1",
            groupId = "notification-coupon-v1",
            containerFactory = "couponKafkaListenerContainerFactory")
    public void coupon(ConsumerRecord<String, byte[]> record) {
        accept(record, "COUPON");
    }

    @KafkaListener(
            id = "campaign-notices",
            topics = "stockdrop.notifications.campaign.v1",
            groupId = "notification-campaign-v1",
            containerFactory = "couponKafkaListenerContainerFactory")
    public void campaign(ConsumerRecord<String, byte[]> record) {
        accept(record, "CAMPAIGN");
    }

    private void accept(ConsumerRecord<String, byte[]> record, String source) {
        BusinessNotice event;
        try {
            if (record.value() == null || record.value().length > 16384) throw invalid();
            event = json.readValue(record.value(), BusinessNotice.class);
        } catch (RuntimeException ex) {
            throw invalid();
        }
        if (event == null
                || !source.equals(event.source())
                || event.accountId() == null
                || !event.accountId().toString().equals(record.key())) throw invalid();
        store.acceptBusiness(event);
    }

    private NotificationException invalid() {
        return new NotificationException(NotificationErrorCode.INVALID_REQUEST);
    }
}
