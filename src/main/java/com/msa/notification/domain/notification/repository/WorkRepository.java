package com.msa.notification.domain.notification.repository;

import com.msa.notification.domain.notification.entity.DeliveryWork;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

/** 복제본은 같은 DB에서 SKIP LOCKED로 작업을 선점합니다. 외부 호출 동안 잠그지 않습니다. */
public interface WorkRepository extends JpaRepository<DeliveryWork, UUID> {
    // 동일 eventId가 다른 소비 경로로 경합해도 기존 payload를 덮어쓰지 않습니다.
    @Modifying
    @Query(
            value =
                    "insert into"
                        + " notification.delivery_work(id,event_id,kind,subscription_key,generation,payload,status,next_at,expires_at,attempts)"
                        + " values"
                        + " (:id,:id,'INTENT','00000000-0000-0000-0000-000000000000',0,:payload,'PENDING',:now,:expiry,0)"
                        + " on conflict do nothing",
            nativeQuery = true)
    int insertIntent(
            @Param("id") UUID id,
            @Param("payload") String payload,
            @Param("now") Instant now,
            @Param("expiry") Instant expiry);

    @Query(
            value =
                    "select * from notification.delivery_work where status in ('PENDING','RETRY')"
                        + " and next_at<=:now and (lease_until is null or lease_until<=:now) order"
                        + " by next_at,id limit 1 for update skip locked",
            nativeQuery = true)
    Optional<DeliveryWork> next(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from DeliveryWork w where w.id=:id")
    Optional<DeliveryWork> lock(@Param("id") UUID id);

    /** 일반 알림도 Kafka ack 전에 같은 DB에 영속화합니다. */
    @Modifying
    @Query(
            value =
                    "insert into"
                        + " notification.delivery_work(id,event_id,kind,subscription_key,generation,payload,status,next_at,expires_at,attempts)"
                        + " values"
                        + " (:id,:id,'NOTICE_INTENT','00000000-0000-0000-0000-000000000000',0,:payload,'PENDING',:now,:expiry,0)"
                        + " on conflict do nothing",
            nativeQuery = true)
    int insertNotice(
            @Param("id") UUID id,
            @Param("payload") String payload,
            @Param("now") Instant now,
            @Param("expiry") Instant expiry);
}
