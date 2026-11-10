package com.msa.notification.global.config.push;

import jakarta.validation.constraints.*;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 알림함 보관 기간이 아니라 외부 푸시 재시도의 기술적 상한입니다. */
@Validated
@ConfigurationProperties("notification.business")
public record BusinessNoticeProperties(@NotNull Duration pushMaxAge) {
    public BusinessNoticeProperties {
        if (pushMaxAge == null) pushMaxAge = Duration.ofDays(7);
        if (pushMaxAge.isNegative()
                || pushMaxAge.isZero()
                || pushMaxAge.compareTo(Duration.ofDays(30)) > 0)
            throw new IllegalArgumentException("일반 알림 푸시 재시도 기간은 0초 초과 30일 이하여야 합니다.");
    }
}
