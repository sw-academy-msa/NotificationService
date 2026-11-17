package com.msa.notification.domain.notification.repository;

import com.msa.notification.domain.notification.entity.ProductAlertSubscription;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

/** 같은 회원·상품 신청은 고정 ID로 한 행만 저장합니다. */
public interface ProductAlertSubscriptionRepository
        extends JpaRepository<ProductAlertSubscription, UUID> {
    @Modifying
    @Query(
            value =
                    "insert into notification.product_alert_subscription(id,account_id,product_id,created_at)"
                            + " values (:id,:account,:product,:now) on conflict(id) do nothing",
            nativeQuery = true)
    void insert(
            @Param("id") UUID id,
            @Param("account") UUID account,
            @Param("product") UUID product,
            @Param("now") Instant now);

    List<ProductAlertSubscription> findByProductId(UUID productId);

    List<ProductAlertSubscription> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    long countByAccountId(UUID accountId);

    @Modifying
    @Query("delete from ProductAlertSubscription s where s.productId in :products")
    void deleteByProducts(@Param("products") Collection<UUID> products);
}
