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

    /** 계정별 신청 한도 검사와 삽입을 직렬화합니다. 트랜잭션이 끝나면 자동으로 풀립니다. */
    @Query(
            value =
                    "select count(*) from (select pg_advisory_xact_lock(hashtextextended(cast(:account as text), 0))) l",
            nativeQuery = true)
    long lockAccount(@Param("account") UUID account);

    List<ProductAlertSubscription> findByProductId(UUID productId);

    List<ProductAlertSubscription> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    long countByAccountId(UUID accountId);

    /** 지울 때 종료 시각을 다시 확인합니다. 조회와 삭제 사이에 판매가 연장된 상품의 신청은 남깁니다. */
    @Modifying
    @Query(
            value =
                    "delete from notification.product_alert_subscription s using notification.product_alert_target t"
                            + " where s.product_id = t.product_id and t.ends_at < :before",
            nativeQuery = true)
    int deleteEnded(@Param("before") Instant before);
}
