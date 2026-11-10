package com.msa.notification.domain.notification.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** 브라우저의 PushSubscription을 받으며 비밀 구독 값은 응답이나 로그에 쓰지 않습니다. */
public record SubscriptionRequest(
        @NotBlank @Size(max = 4096) String endpoint,
        @Positive @Max(253402300799999L) Long expirationTime,
        @Valid @NotNull Keys keys) {
    public record Keys(
            @NotBlank @Size(max = 100) String p256dh, @NotBlank @Size(max = 32) String auth) {
        @Override
        public String toString() {
            return "Keys[REDACTED]";
        }
    }

    @Override
    public String toString() {
        return "SubscriptionRequest[REDACTED]";
    }
}
