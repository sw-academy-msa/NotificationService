package com.msa.notification.domain.notification.service;

import com.msa.notification.domain.notification.dto.response.*;

import java.time.Instant;

/** 수신 계약 검증과 알림 표시 문구를 정의하며 원본 업무 상태를 변경하지 않습니다. */
public interface BusinessNoticePolicy {
    void validate(BusinessNotice event);

    NoticeContent content(BusinessNotice event);

    String cursor(BusinessNotice event);

    Instant pushDeadline(BusinessNotice event);
}
