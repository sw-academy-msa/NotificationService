package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;

import org.junit.jupiter.api.Test;

import java.util.List;

/** 여덟 광고 문구와 예산 소진 대체 문구에 고정된 광고 표시를 검증합니다. */
class CouponNoticeTextTest {
    @Test
    void allCopyUsesAdPrefixAndFallbackOmitsCouponPromise() {
        for (String suffix :
                List.of(
                        "SALE_OPEN",
                        "CHECKOUT_REMINDER",
                        "STOCK_REMINDER",
                        "SIMILAR_DROP",
                        "CATEGORY_NEW",
                        "EXPIRING",
                        "MEMBER_ARRIVED")) {
            var e =
                    BusinessNotice.builder()
                            .source("COUPON")
                            .notificationType("COUPON_" + suffix)
                            .build();
            var c = CouponNoticeText.content(e, "상품", "신발", 3);
            assertThat(c.title()).startsWith("(광고) ").hasSizeLessThanOrEqualTo(120);
            assertThat(c.body()).isNotBlank();
        }
        for (String suffix :
                List.of(
                        "SALE_OPEN",
                        "CHECKOUT_REMINDER",
                        "STOCK_REMINDER",
                        "SIMILAR_DROP",
                        "CATEGORY_NEW",
                        "SEARCH_MATCH")) {
            var c =
                    CouponNoticeText.content(
                            BusinessNotice.builder()
                                    .source("CAMPAIGN")
                                    .notificationType("CAMPAIGN_" + suffix)
                                    .build(),
                            "상품",
                            "신발",
                            3);
            assertThat(c.title()).startsWith("(광고) ");
            assertThat(c.body()).doesNotContain("쿠폰");
        }
    }
}
