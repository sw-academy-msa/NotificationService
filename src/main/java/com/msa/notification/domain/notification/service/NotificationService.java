package com.msa.notification.domain.notification.service;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.*;

import java.util.*;

/** 인증된 사용자의 알림과 구독만 관리합니다. 알림 생성은 Kafka 입력에 한정합니다. */
public interface NotificationService {
    Messages list(UUID account, int page, int size);

    Message get(UUID account, UUID id);

    Count unread(UUID account);

    Changed read(UUID account, UUID id);

    Changed readAll(UUID account);

    Subscription subscribe(UUID account, SubscriptionRequest request);

    List<Subscription> subscriptions(UUID account);

    void unsubscribe(UUID account, UUID id);

    Configuration configuration();
}
