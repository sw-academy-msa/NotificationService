package com.msa.notification.domain.notification.entity;

import jakarta.persistence.*;

import lombok.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** 회원이 상품 상세에서 신청한 상품 알림입니다. ID는 회원·상품 조합으로 정해 중복 신청을 하나로 합칩니다. */
@Entity
@Table(name = "product_alert_subscription", schema = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductAlertSubscription {
    @Id private UUID id;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Instant createdAt;

    @Builder
    ProductAlertSubscription(UUID accountId, UUID productId, Instant createdAt) {
        this.id = key(accountId, productId);
        this.accountId = accountId;
        this.productId = productId;
        this.createdAt = createdAt;
    }

    public static UUID key(UUID accountId, UUID productId) {
        return UUID.nameUUIDFromBytes(
                ("product-alert:" + accountId + ":" + productId).getBytes(StandardCharsets.UTF_8));
    }
}
