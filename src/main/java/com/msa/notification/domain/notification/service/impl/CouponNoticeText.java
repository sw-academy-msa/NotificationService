package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.exception.*;

/** 광고 알림의 고정 문구에 검증된 공개 상품 정보만 넣습니다. */
public final class CouponNoticeText {
    private CouponNoticeText() {}

    public static NoticeContent content(
            BusinessNotice e, String name, String category, long remaining) {
        name = name.substring(0, Math.min(30, name.length()));
        category = category.substring(0, Math.min(30, category.length()));
        boolean coupon = "COUPON".equals(e.source());
        String title, body;
        switch (e.notificationType().replace("COUPON_", "").replace("CAMPAIGN_", "")) {
            case "SALE_OPEN" -> {
                title = "알림 신청한 상품 오픈";
                body = name + " 판매가 시작됐어요." + (coupon ? " 전용 쿠폰을 넣어 드렸어요." : "");
            }
            case "CHECKOUT_REMINDER" -> {
                title = "결제를 마치지 못한 상품";
                body = name + "을 다시 담아 보세요." + (coupon ? " 전용 쿠폰이 도착했어요." : "");
            }
            case "STOCK_REMINDER" -> {
                title = "남은 수량 " + remaining + "개";
                body = "여러 번 본 " + name + "이 얼마 남지 않았어요." + (coupon ? " 쿠폰도 준비했어요." : "");
            }
            case "SIMILAR_DROP" -> {
                title = "비슷한 한정판 오픈";
                body = "품절로 놓친 상품과 비슷한 " + name + "이 오픈했어요." + (coupon ? " 전용 쿠폰을 확인해 보세요." : "");
            }
            case "SEARCH_MATCH" -> {
                title = "찾던 상품이 등록됐어요";
                body = "검색하신 상품과 비슷한 " + name + "이 새로 등록됐어요.";
            }
            case "CATEGORY_NEW" -> {
                title = "관심 카테고리 새 상품";
                body =
                        "관심 있게 본 "
                                + category
                                + "에 "
                                + name
                                + "이 나왔어요."
                                + (coupon ? " 전용 쿠폰도 받아 가세요." : "");
            }
            case "EXPIRING" -> {
                title = "쿠폰 만료 임박";
                body = "받은 쿠폰이 곧 만료돼요. 쿠폰함에서 확인해 주세요.";
            }
            case "MEMBER_ARRIVED" -> {
                title = "회원 전용 쿠폰 도착";
                body = "이번 달 회원 쿠폰이 도착했어요. 쿠폰함에서 확인해 주세요.";
            }
            default -> throw new NotificationException(NotificationErrorCode.INVALID_REQUEST);
        }
        return NoticeContent.builder().title("(광고) " + title).body(body).build();
    }
}
