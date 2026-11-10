package com.msa.notification.domain.notification.entity;

import jakarta.persistence.*;

import lombok.*;

/** 수신자/원본별 최신 알림 버전입니다. 과거 알림함 이력은 보존하고 늦은 이전 상태 푸시만 억제합니다. */
@Entity
@Table(name = "notice_cursor", schema = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NoticeCursor {
    @Id
    @Column(length = 120)
    private String id;

    @Column(nullable = false)
    private long latestVersion;
}
