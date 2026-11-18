package com.msa.notification.domain.notification.service;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;

/** 검증과 저장 암호화 책임입니다. 개인 구독 URL을 평문 로그에 남기지 않습니다. */
public interface CredentialService {
    String validateAndHash(SubscriptionRequest request);

    String encrypt(SubscriptionRequest request, String aad);

    SubscriptionRequest decrypt(String value, String aad);
}
