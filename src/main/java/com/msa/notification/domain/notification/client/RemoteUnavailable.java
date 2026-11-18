package com.msa.notification.domain.notification.client;

/** 응답 본문, URL과 토큰을 예외 메시지에 담지 않는 재시도 신호입니다. */
public class RemoteUnavailable extends RuntimeException {
    public RemoteUnavailable() {
        super("NOTIFICATION_DEPENDENCY_UNAVAILABLE");
    }
}
