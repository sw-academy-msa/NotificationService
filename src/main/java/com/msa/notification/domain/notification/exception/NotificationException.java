package com.msa.notification.domain.notification.exception;

import com.msa.notification.global.error.BusinessException;

/** 알림 도메인의 예상 가능한 실패만 공통 오류 응답으로 변환합니다. */
public class NotificationException extends BusinessException {
    public NotificationException(NotificationErrorCode code) {
        super(code);
    }
}
