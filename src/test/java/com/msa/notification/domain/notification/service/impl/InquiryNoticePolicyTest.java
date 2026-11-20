package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.global.config.push.BusinessNoticeProperties;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 문의 알림의 수신 역할, 이동 정보 및 개인정보 없는 고정 문구를 검증합니다. */
class InquiryNoticePolicyTest {
    private final BusinessNoticePolicyImpl policy =
            new BusinessNoticePolicyImpl(new BusinessNoticeProperties(Duration.ofDays(7)));

    static BusinessNotice event(String source, String action, String target, UUID related) {
        UUID account = UUID.randomUUID(), inquiry = UUID.randomUUID();
        String kind = source + "_INQUIRY_" + action;
        String identity = source + ":" + kind + ":" + inquiry + ":1:" + account;
        return new BusinessNotice(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                1, "BUSINESS_NOTIFICATION", source, account, kind, inquiry, 1,
                target, inquiry, related, null, Instant.parse("2030-01-01T00:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
        "PRODUCT, CREATED, SELLER_PRODUCT_INQUIRY, 새 상품 문의, 구매자가 상품 문의를 남겼어요. 판매자 문의함에서 답변해 주세요.",
        "PRODUCT, ANSWERED, PRODUCT_INQUIRY, 상품 문의 답변, 판매자가 상품 문의에 답변했어요. 내 문의 내역에서 확인해 주세요.",
        "ORDER, CREATED, SELLER_ORDER_INQUIRY, 새 주문 문의, 구매자가 주문 문의를 남겼어요. 판매자 문의함에서 답변해 주세요.",
        "ORDER, ANSWERED, ORDER_INQUIRY, 주문 문의 답변, 판매자가 주문 문의에 답변했어요. 내 문의 내역에서 확인해 주세요."
    })
    void acceptsOnlyCorrectRoleAndRequiredRelatedId(
            String source, String action, String target, String title, String body) {
        var event = event(source, action, target, UUID.randomUUID());
        assertThatCode(() -> policy.validate(event)).doesNotThrowAnyException();
        assertThat(policy.content(event).title()).isEqualTo(title);
        assertThat(policy.content(event).body()).isEqualTo(body);
        assertThatThrownBy(() -> policy.validate(event(source, action, target, null)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.validate(event(source, action, "ORDER", UUID.randomUUID())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.validate(event(source, action,
                target.startsWith("SELLER_") ? source + "_INQUIRY" : "SELLER_" + source + "_INQUIRY",
                UUID.randomUUID()))).isInstanceOf(RuntimeException.class);
    }
}
