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
                                    && "SELLER_APPLICATION".equals(event.targetType());
                    case "PRODUCT" ->
                            Set.of("PRODUCT_APPROVED", "PRODUCT_REJECTED")
                                                    .contains(event.notificationType())
                                            && "SELLER_PRODUCT".equals(event.targetType())
                                    || Set.of(
                                                            "PRODUCT_REVISION_APPROVED",
                                                            "PRODUCT_REVISION_REJECTED")
                                                    .contains(event.notificationType())
                                            && "SELLER_PRODUCT_REVISION".equals(event.targetType())
                                            && event.relatedId() != null;
                    case "ORDER" ->
                            "ORDER_CONFIRMED".equals(event.notificationType())
                                            && Set.of("ORDER", "SELLER_ORDER")
                                                    .contains(event.targetType())
                                    || Set.of("ORDER_SHIPPED", "ORDER_DELIVERED")
                                                    .contains(event.notificationType())
                                            && "ORDER".equals(event.targetType());
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

    public Instant pushDeadline(BusinessNotice event) {
        Instant expires = event.occurredAt().plus(properties.pushMaxAge());
        if ("SELLER_CORRECTION_REQUESTED".equals(event.notificationType())
                && event.deadline().isBefore(expires)) return event.deadline();
        return expires;
    }

    public NoticeContent content(BusinessNotice event) {
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
            default -> throw invalid();
        }
        return NoticeContent.builder().title(title).body(body).build();
    }

    private NotificationException invalid() {
        return new NotificationException(NotificationErrorCode.INVALID_REQUEST);
    }
}
