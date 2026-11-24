package com.msa.notification.domain.notification.consumer;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.service.WorkStore;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** 회원제 알림 토픽만 유한 재시도와 DLT로 소비하며 기존 업무 알림 토픽의 재시도는 바꾸지 않습니다. */
@Component
@ConditionalOnProperty(name = "notification.membership-consumer-enabled", havingValue = "true")
public class MembershipNoticeConsumer {
    private final WorkStore store;
    private final ObjectMapper json;

    public MembershipNoticeConsumer(WorkStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    @KafkaListener(
            id = "membership-notices",
            topics = "stockdrop.notifications.membership.v1",
            groupId = "notification-membership-v1",
            containerFactory = "membershipKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, byte[]> record) {
        BusinessNotice event;
        try {
            if (record.value() == null || record.value().length > 16384) throw invalid();
            event = json.readValue(record.value(), BusinessNotice.class);
        } catch (RuntimeException failure) {
            throw invalid();
        }
        if (!"MEMBERSHIP".equals(event.source())
                || event.aggregateId() == null
                || !event.aggregateId().toString().equals(record.key())) throw invalid();
        // 형식과 멱등 충돌은 즉시 DLT, DB 장애는 재시도 후 DLT로 보냅니다.
        store.acceptBusiness(event);
    }

    private NotificationException invalid() {
        return new NotificationException(NotificationErrorCode.INVALID_REQUEST);
    }
}
