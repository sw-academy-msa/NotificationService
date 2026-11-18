package com.msa.notification.domain.notification.client;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.PushResult;

/** 전송 성공은 푸시 서비스 접수이며 단말 수신이나 읽음을 의미하지 않습니다. */
public interface PushSender {
    PushResult send(SubscriptionRequest subscription, byte[] payload, int ttl, String topic);
}
