package com.msa.notification.global.config.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** VAPID와 저장 암호화 키는 Account RSA 키와 별개이며 문자열 출력에서 감춥니다. */
@ConfigurationProperties("notification.push")
public record PushProperties(
        boolean enabled,
        String publicKey,
        String privateKey,
        String subject,
        String encryptionKey) {
    @Override
    public String toString() {
        return "PushProperties[REDACTED]";
    }
}
