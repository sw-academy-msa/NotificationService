package com.msa.notification.domain.notification.service;

import com.msa.notification.domain.notification.dto.response.NotificationResponses.ProductAlert;

import tools.jackson.databind.JsonNode;

import java.util.*;

/** 상품 알림 신청과, 신청자에게 보낼 판매 시작·가격 인하·수량 임박 알림 생성을 소유합니다. */
public interface ProductAlertService {
    ProductAlert subscribe(UUID account, UUID product);

    void unsubscribe(UUID account, UUID product);

    ProductAlert status(UUID account, UUID product);

    List<ProductAlert> list(UUID account);

    /** 상품 공개 이벤트를 반영하고 가격 인하·수량 임박 알림을 만듭니다. */
    void acceptProduct(String key, JsonNode event);

    /** 판매 시작 10분 전에 들어온 상품의 시작 알림을 한 묶음(최대 50개) 만듭니다. 만든 상품 수를 반환합니다. */
    int dispatchStarts();

    /** 판매가 끝나고 하루 지난 상품의 신청을, 30일 지난 상품의 상태를 지웁니다. 지운 신청 수를 반환합니다. */
    int cleanup();
}
