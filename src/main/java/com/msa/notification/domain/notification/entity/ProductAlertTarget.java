package com.msa.notification.domain.notification.entity;

import jakarta.persistence.*;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** 상품 공개 이벤트에서 알림 판단에 필요한 값만 보관합니다. 알림을 보낸 여부를 함께 저장해 재전송을 막습니다. */
@Entity
@Table(name = "product_alert_target", schema = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductAlertTarget {
    @Id private UUID productId;

    @Column(nullable = false)
    private UUID sellerId;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private Instant endsAt;

    @Column(nullable = false)
    private boolean startNoticed;

    @Column(nullable = false)
    private boolean lowStockNoticed;

    @Column(nullable = false)
    private Instant updatedAt;

    @Builder
    ProductAlertTarget(UUID productId, UUID sellerId, Instant now) {
        this.productId = productId;
        this.sellerId = sellerId;
        this.version = -1;
        this.updatedAt = now;
    }

    /** 판매 시각이 바뀌면 바뀐 일정 기준으로 시작 알림을 다시 보낼 수 있게 합니다. */
    public void apply(long version, long price, Instant startsAt, Instant endsAt, Instant now) {
        if (!startsAt.equals(this.startsAt)) startNoticed = false;
        this.version = version;
        this.price = price;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.updatedAt = now;
    }

    public void markStartNoticed() {
        startNoticed = true;
    }

    public void markLowStockNoticed() {
        lowStockNoticed = true;
    }
}
