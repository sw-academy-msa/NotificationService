package com.msa.notification.domain.notification.repository;

import com.msa.notification.domain.notification.entity.SubscriberGuard;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/** 계정별 구독 한도 경쟁만 직렬화합니다. native SQL은 PostgreSQL의 원자적 최초 삽입에 한정합니다. */
public interface GuardRepository extends JpaRepository<SubscriberGuard, UUID> {
    @Modifying
    @Query(
            value =
                    "insert into notification.subscriber_guard(id) values (:id) on conflict do"
                            + " nothing",
            nativeQuery = true)
    void ensure(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from SubscriberGuard g where g.id=:id")
    SubscriberGuard lock(@Param("id") UUID id);
}
