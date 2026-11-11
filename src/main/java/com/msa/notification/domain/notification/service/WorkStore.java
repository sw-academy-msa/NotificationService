package com.msa.notification.domain.notification.service;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.*;

import java.util.*;

/** 영속 입력, 선점과 완료를 짧은 로컬 트랜잭션으로 처리합니다. */
public interface WorkStore {
    void accept(Candidate event);

    void acceptBusiness(BusinessNotice event);

    void readyBusiness(Lease work);

    boolean currentBusiness(BusinessNotice event);

    Optional<Lease> claim();

    void ready(Lease work, String productName);

    void finish(Lease work, String state, int httpStatus, long retryAfterSeconds);

    Optional<SubscriptionRequest> credentials(Lease work);
}
