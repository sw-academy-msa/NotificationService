package com.msa.notification.domain.notification.repository;

import com.msa.notification.domain.notification.entity.ProductAlertTarget;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

/** 같은 상품의 이벤트 반영과 시작 알림 예약은 행 잠금으로 직렬화합니다. */
public interface ProductAlertTargetRepository extends JpaRepository<ProductAlertTarget, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ProductAlertTarget t where t.productId=:id")
    Optional<ProductAlertTarget> lock(@Param("id") UUID id);

    @Modifying
    @Query(
            value =
                    "insert into notification.product_alert_target(product_id,seller_id,version,price,"
                            + "starts_at,ends_at,start_noticed,low_stock_noticed,updated_at) values"
                            + " (:id,:seller,-1,0,:now,:now,false,false,:now) on conflict(product_id) do"
                            + " nothing",
            nativeQuery = true)
    void insertIfAbsent(
            @Param("id") UUID id, @Param("seller") UUID seller, @Param("now") Instant now);

    /** 다른 인스턴스와 겹치지 않도록 잠긴 행은 건너뜁니다. */
    @Query(
            value =
                    "select * from notification.product_alert_target where start_noticed=false and"
                            + " version>=0 and starts_at<=:until and starts_at>:after order by starts_at"
                            + " limit :size for update skip locked",
            nativeQuery = true)
    List<ProductAlertTarget> dueStarts(
            @Param("until") Instant until, @Param("after") Instant after, @Param("size") int size);

    /** 오래전에 끝난 상품 상태를 지웁니다. 늦게 도착한 이전 버전이 다시 미래 판매로 살아나지 않도록 넉넉히 보관한 뒤 지웁니다. */
    @Modifying
    @Query("delete from ProductAlertTarget t where t.endsAt < :before")
    int deleteEnded(@Param("before") Instant before);
}
