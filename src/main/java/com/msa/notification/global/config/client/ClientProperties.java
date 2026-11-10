package com.msa.notification.global.config.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 내부 주소는 배포별로 주입하며 서비스 토큰은 기존 Account 발급 계약을 사용합니다. */
@ConfigurationProperties("notification.clients")
public record ClientProperties(
        String account,
        String ai,
        String productQuery,
        String clientSecret,
        String serviceSubject) {
    @Override
    public String toString() {
        return "ClientProperties[REDACTED]";
    }
}
