package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.dto.response.*;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.service.BusinessNoticePolicy;
import com.msa.notification.global.config.push.BusinessNoticeProperties;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 업무 알림 문구는 서버가 정합니다. 심사 사유, 연락처나 결제 정보를 푸시 본문에 복제하지 않습니다. */
@Service
public class BusinessNoticePolicyImpl implements BusinessNoticePolicy {
    private final BusinessNoticeProperties properties;

    public BusinessNoticePolicyImpl(BusinessNoticeProperties properties) {
        this.properties = properties;
    }

    public void validate(BusinessNotice event) {
        if (event == null
                || event.eventId() == null
                || event.schemaVersion() != 1
                || !"BUSINESS_NOTIFICATION".equals(event.type())
                || event.accountId() == null
                || event.aggregateId() == null
                || event.targetId() == null
                || event.aggregateVersion() < 0
                || event.occurredAt() == null
                || event.source() == null
                || event.notificationType() == null
                || event.targetType() == null) throw invalid();
        boolean target =
                switch (event.source()) {
                    case "ACCOUNT" ->
                            Set.of(
                                                            "SELLER_APPROVED",
                                                            "SELLER_REJECTED",
                                                            "SELLER_CORRECTION_REQUESTED",
                                                            "SELLER_CORRECTION_EXPIRED")
                                                    .contains(event.notificationType())
                                            && "SELLER_APPLICATION".equals(event.targetType())
                                    // 운영팀 1:1 문의 답변
                                    || "SUPPORT_ANSWERED".equals(event.notificationType())
                                            && "SUPPORT_INQUIRY".equals(event.targetType());
                    case "PRODUCT" ->
                            Set.of("PRODUCT_APPROVED", "PRODUCT_REJECTED")
                                                    .contains(event.notificationType())
                                            && "SELLER_PRODUCT".equals(event.targetType())
                                    || Set.of(
                                                            "PRODUCT_REVISION_APPROVED",
                                                            "PRODUCT_REVISION_REJECTED")
                                                    .contains(event.notificationType())
                                            && "SELLER_PRODUCT_REVISION".equals(event.targetType())
                                            && event.relatedId() != null
                                    || inquiryTarget(event, "PRODUCT");
                    case "ORDER" ->
                            "ORDER_CONFIRMED".equals(event.notificationType())
                                            && Set.of("ORDER", "SELLER_ORDER")
                                                    .contains(event.targetType())
                                    || Set.of("ORDER_SHIPPED", "ORDER_DELIVERED")
                                                    .contains(event.notificationType())
                                            && "ORDER".equals(event.targetType())
                                    || inquiryTarget(event, "ORDER");
                    // 상품 알림 신청자에게 보내는 알림입니다. 푸시 기한(deadline)이 반드시 있어야 합니다.
                    case "PRODUCT_ALERT" ->
                            Set.of("SALE_STARTING", "PRICE_DROPPED", "STOCK_LOW")
                                            .contains(event.notificationType())
                                    && "PRODUCT".equals(event.targetType())
                                    && event.deadline() != null;
                    // 회원제 알림은 구독 ID를 대상으로 하며 만료 예정은 혜택 종료 시각이 있어야 합니다.
                    case "MEMBERSHIP" ->
                            Set.of(
                                                    "MEMBERSHIP_PAYMENT_FAILED",
                                                    "MEMBERSHIP_RENEWED",
                                                    "MEMBERSHIP_EXPIRING",
                                                    "MEMBERSHIP_EXPIRED")
                                            .contains(event.notificationType())
                                    && "MEMBERSHIP".equals(event.targetType())
                                    && event.relatedId() == null
                                    && (!"MEMBERSHIP_EXPIRING".equals(event.notificationType())
                                            || event.deadline() != null);
                    case "COUPON" ->
                            Set.of(
                                                    "COUPON_SALE_OPEN",
                                                    "COUPON_CHECKOUT_REMINDER",
                                                    "COUPON_STOCK_REMINDER",
                                                    "COUPON_SIMILAR_DROP",
                                                    "COUPON_CATEGORY_NEW",
                                                    "COUPON_EXPIRING",
                                                    "COUPON_MEMBER_ARRIVED")
                                            .contains(event.notificationType())
                                    && event.deadline() != null
                                    && (Set.of("COUPON_EXPIRING", "COUPON_MEMBER_ARRIVED")
                                                    .contains(event.notificationType())
                                            ? "COUPON".equals(event.targetType())
                                            : "PRODUCT".equals(event.targetType()));
                    case "CAMPAIGN" ->
                            Set.of(
                                                    "CAMPAIGN_SALE_OPEN",
                                                    "CAMPAIGN_CHECKOUT_REMINDER",
                                                    "CAMPAIGN_STOCK_REMINDER",
                                                    "CAMPAIGN_SIMILAR_DROP",
                                                    "CAMPAIGN_CATEGORY_NEW",
                                                    "CAMPAIGN_SEARCH_MATCH")
                                            .contains(event.notificationType())
                                    && event.deadline() != null
                                    && "PRODUCT".equals(event.targetType());
                    default -> false;
                };
        String identity =
                event.source()
                        + ":"
                        + event.notificationType()
                        + ":"
                        + event.aggregateId()
                        + ":"
                        + event.aggregateVersion()
                        + ":"
                        + event.accountId();
        if (!target
                || !event.eventId()
                        .equals(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))))
            throw invalid();
        if (event.notificationType().startsWith("SELLER_CORRECTION_")
                && (event.relatedId() == null || event.deadline() == null)) throw invalid();
    }

    public String cursor(BusinessNotice event) {
        return event.source() + ":" + event.accountId() + ":" + event.aggregateId();
    }

    /** 문의 원본 ID와 관련 상품/주문 ID를 분리하여 다른 업무의 버전과 섞이지 않게 합니다. */
    private boolean inquiryTarget(BusinessNotice event, String domain) {
        return event.relatedId() != null
                && event.aggregateId().equals(event.targetId())
                && ((domain + "_INQUIRY_CREATED").equals(event.notificationType())
                                && ("SELLER_" + domain + "_INQUIRY").equals(event.targetType())
                        || (domain + "_INQUIRY_ANSWERED").equals(event.notificationType())
                                && (domain + "_INQUIRY").equals(event.targetType()));
    }

    public Instant pushDeadline(BusinessNotice event) {
        Instant expires = event.occurredAt().plus(properties.pushMaxAge());
        if (("SELLER_CORRECTION_REQUESTED".equals(event.notificationType())
                        || "PRODUCT_ALERT".equals(event.source())
                        || "MEMBERSHIP_EXPIRING".equals(event.notificationType())
                        || "COUPON".equals(event.source())
                        || "CAMPAIGN".equals(event.source()))
                && event.deadline().isBefore(expires)) return event.deadline();
        return expires;
    }

    public NoticeContent content(BusinessNotice event) {
        if ("COUPON".equals(event.source()) || "CAMPAIGN".equals(event.source()))
            return CouponNoticeText.content(event, "상품", "관심 카테고리", 1);
        String title, body;
        switch (event.notificationType()) {
            case "SELLER_APPROVED" -> {
                title = "판매자 심사 승인";
                body = "판매자 심사 승인 결과를 확인해 주세요.";
            }
            case "SELLER_REJECTED" -> {
                title = "판매자 심사 반려";
                body = "판매자 신청 내역에서 심사 결과를 확인해 주세요.";
            }
            case "SELLER_CORRECTION_REQUESTED" -> {
                title = "판매자 신청 보완 요청";
                body =
                        "보완 항목을 확인해 "
                                + DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
                                        .withZone(ZoneId.of("Asia/Seoul"))
                                        .format(event.deadline())
                                + "(한국 시간)까지 제출해 주세요.";
            }
            case "SELLER_CORRECTION_EXPIRED" -> {
                title = "판매자 신청 보완 기한 만료";
                body = "보완 제출 기한이 지났습니다. 신청 내역을 확인해 주세요.";
            }
            case "PRODUCT_APPROVED" -> {
                title = "상품 심사 승인";
                body = "상품 심사 승인 결과를 확인해 주세요.";
            }
            case "PRODUCT_REJECTED" -> {
                title = "상품 심사 반려";
                body = "상품 관리에서 심사 결과를 확인해 주세요.";
            }
            case "PRODUCT_REVISION_APPROVED" -> {
                title = "상품 수정 심사 승인";
                body = "상품 수정 심사 승인 결과를 확인해 주세요.";
            }
            case "PRODUCT_REVISION_REJECTED" -> {
                title = "상품 수정 심사 반려";
                body = "상품 관리에서 수정 심사 결과를 확인해 주세요.";
            }
            // 질문과 답변 원문을 푸시나 알림함에 복제하지 않습니다.
            case "PRODUCT_INQUIRY_CREATED" -> {
                title = "새 상품 문의";
                body = "구매자가 상품 문의를 남겼어요. 판매자 문의함에서 답변해 주세요.";
            }
            case "PRODUCT_INQUIRY_ANSWERED" -> {
                title = "상품 문의 답변";
                body = "판매자가 상품 문의에 답변했어요. 내 문의 내역에서 확인해 주세요.";
            }
            case "ORDER_INQUIRY_CREATED" -> {
                title = "새 주문 문의";
                body = "구매자가 주문 문의를 남겼어요. 판매자 문의함에서 답변해 주세요.";
            }
            case "ORDER_INQUIRY_ANSWERED" -> {
                title = "주문 문의 답변";
                body = "판매자가 주문 문의에 답변했어요. 내 문의 내역에서 확인해 주세요.";
            }
            case "SUPPORT_ANSWERED" -> {
                title = "1:1 문의 답변";
                body = "운영팀이 1:1 문의에 답변했어요. 문의 내역에서 확인해 주세요.";
            }
            case "ORDER_CONFIRMED" -> {
                title = "SELLER_ORDER".equals(event.targetType()) ? "새 주문 확정" : "주문 확정";
                body = "결제와 재고 확정이 완료된 주문 내역을 확인해 주세요.";
            }
            case "ORDER_SHIPPED" -> {
                title = "배송 시작 안내";
                body = "주문한 상품의 배송이 시작되었습니다. 주문 내역을 확인해 주세요.";
            }
            case "ORDER_DELIVERED" -> {
                title = "배송 완료 안내";
                body = "판매자가 배송 완료를 등록했습니다. 주문 내역을 확인해 주세요.";
            }
            case "SALE_STARTING" -> {
                title = "판매 시작 10분 전";
                body = "알림을 신청한 상품의 선착순 판매가 곧 시작됩니다.";
            }
            case "PRICE_DROPPED" -> {
                title = "관심 상품 가격 인하";
                body = "알림을 신청한 상품의 가격이 내려갔습니다. 상품 정보를 확인해 주세요.";
            }
            case "STOCK_LOW" -> {
                title = "한정 수량 마감 임박";
                body = "알림을 신청한 상품의 남은 수량이 얼마 남지 않았습니다.";
            }
            // 금액, 카드, 실패 사유는 본문에 넣지 않습니다.
            case "MEMBERSHIP_PAYMENT_FAILED" -> {
                title = "멤버십 결제 실패";
                body =
                        event.deadline() == null
                                ? "멤버십 결제에 실패했어요. 결제 카드를 확인해 주세요."
                                : "멤버십 정기 결제에 실패했어요. "
                                        + seoul(event.deadline())
                                        + "(한국 시간)까지 결제되지 않으면 혜택이 종료됩니다.";
            }
            case "MEMBERSHIP_RENEWED" -> {
                title = "멤버십 갱신 완료";
                body = "멤버십 정기 결제가 완료되어 혜택이 연장되었어요.";
            }
            case "MEMBERSHIP_EXPIRING" -> {
                title = "멤버십 종료 예정";
                body = "멤버십 혜택이 " + seoul(event.deadline()) + "(한국 시간)에 종료됩니다.";
            }
            case "MEMBERSHIP_EXPIRED" -> {
                title = "멤버십 종료";
                body = "멤버십 혜택이 종료되었어요. 멤버십 화면에서 다시 가입할 수 있어요.";
            }
            default -> throw invalid();
        }
        return NoticeContent.builder().title(title).body(body).build();
    }

    private String seoul(Instant time) {
        return DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
                .withZone(ZoneId.of("Asia/Seoul"))
                .format(time);
    }

    private NotificationException invalid() {
        return new NotificationException(NotificationErrorCode.INVALID_REQUEST);
    }
}
