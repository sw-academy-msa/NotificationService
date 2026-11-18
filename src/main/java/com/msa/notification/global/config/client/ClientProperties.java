package com.msa.notification.global.config.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 내부 호출 대상은 Eureka 서비스 ID이며 고정 주소를 두지 않습니다. scheme 기본값은 https이고 로컬·테스트만 http를 씁니다. 서비스 토큰은
 * 기존 Account 발급 계약을 사용합니다.
 */
@ConfigurationProperties("notification.clients")
public record ClientProperties(
        String scheme,
        String account,
        String ai,
        String productQuery,
        String clientSecret,
        String serviceSubject) {
    public ClientProperties {
        scheme = scheme == null || scheme.isBlank() ? "https" : scheme;
    }

    @Override
    public String toString() {
        return "ClientProperties[REDACTED]";
    }
}
