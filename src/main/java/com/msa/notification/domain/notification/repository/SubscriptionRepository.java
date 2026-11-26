package com.msa.notification.domain.notification.repository;

import com.msa.notification.domain.notification.entity.PushSubscription;

import org.springframework.data.jpa.repository.*;

import java.util.*;

/** 구독 접근은 항상 소유 계정과 함께 검사하며 전역 endpoint hash로 중복을 막습니다. */
public interface SubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    /** 만료되지 않은 구독 계정을 중복 없이 일정 크기로 열거합니다. */
    @Query(
            "select distinct s.accountId from PushSubscription s where s.active=true "
                    + "and (s.expiresAt is null or s.expiresAt>:now) "
                    + "and (:after is null or s.accountId>:after) order by s.accountId")
    List<UUID> claimAudience(
            @org.springframework.data.repository.query.Param("after") UUID after,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            org.springframework.data.domain.Pageable page);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PushSubscription s where s.id=:id")
    Optional<PushSubscription> lockById(
            @org.springframework.data.repository.query.Param("id") UUID id);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PushSubscription s where s.endpointHash=:hash")
    Optional<PushSubscription> lockByHash(
            @org.springframework.data.repository.query.Param("hash") String hash);

    Optional<PushSubscription> findByEndpointHash(String hash);

    List<PushSubscription> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    List<PushSubscription> findByAccountIdAndActiveTrue(UUID accountId);
}
