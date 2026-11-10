package com.msa.notification.domain.notification.repository;

import com.msa.notification.domain.notification.entity.InboxMessage;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** 읽음 갱신은 조건부 UPDATE로 첫 읽음 시간을 유지합니다. */
public interface InboxRepository extends JpaRepository<InboxMessage, UUID> {
    Page<InboxMessage> findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable pageable);

    long countByAccountIdAndReadAtIsNull(UUID accountId);

    boolean existsByEventId(UUID eventId);

    @Modifying
    @Query(
            "update InboxMessage m set m.readAt=:now where m.id=:id and m.accountId=:account and"
                    + " m.readAt is null")
    int read(@Param("id") UUID id, @Param("account") UUID account, @Param("now") Instant now);

    @Modifying
    @Query(
            "update InboxMessage m set m.readAt=:now where m.accountId=:account and m.readAt is"
                    + " null and m.createdAt<=:now")
    int readAll(@Param("account") UUID account, @Param("now") Instant now);
}
