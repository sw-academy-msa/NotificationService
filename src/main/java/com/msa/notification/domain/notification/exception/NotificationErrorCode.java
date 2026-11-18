package com.msa.notification.domain.notification.exception;

import com.msa.notification.global.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** 잘못된 구독이나 소유권 오류에 비밀 URL을 포함하지 않습니다. */
public enum NotificationErrorCode implements ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "NOTIFICATION_INVALID_REQUEST", "알림 요청이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "NOTIFICATION_FORBIDDEN", "본인의 알림과 구독만 관리할 수 있습니다."),
    CONFLICT(HttpStatus.CONFLICT, "NOTIFICATION_CONFLICT", "구독 또는 이벤트가 기존 정보와 충돌합니다."),
    UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE, "NOTIFICATION_UNAVAILABLE", "알림 설정을 확인한 뒤 다시 시도해 주세요.");
    private final HttpStatus status;
    private final String code;
    private final String message;

    NotificationErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
