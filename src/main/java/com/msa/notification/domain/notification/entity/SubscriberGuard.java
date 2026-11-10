package com.msa.notification.domain.notification.entity;

import jakarta.persistence.*;

import lombok.*;

import java.util.UUID;

/** Notification 소유 계정별 잠금입니다. Account 테이블과 FK로 연결하지 않습니다. */
@Entity
@Table(name = "subscriber_guard", schema = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriberGuard {
    @Id private UUID id;
}
