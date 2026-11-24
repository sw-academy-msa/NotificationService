package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.global.config.push.BusinessNoticeProperties;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;

/** Account의 회원제 알림을 서버 문구로 보여 주고 대상과 기한 계약을 검사합니다. */
class MembershipNoticePolicyTest {
    private final BusinessNoticePolicyImpl policy =
            new BusinessNoticePolicyImpl(new BusinessNoticeProperties(Duration.ofDays(7)));
    private final Instant occurred = Instant.parse("2030-01-10T00:00:00Z");

    private BusinessNotice notice(String type, String target, Instant deadline) {
        UUID account = UUID.randomUUID(), aggregate = UUID.randomUUID();
        String identity = "MEMBERSHIP:" + type + ":" + aggregate + ":3:" + account;
        return BusinessNotice.builder()
                .eventId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)))
                .schemaVersion(1)
                .type("BUSINESS_NOTIFICATION")
                .source("MEMBERSHIP")
                .accountId(account)
                .notificationType(type)
                .aggregateId(aggregate)
                .aggregateVersion(3)
                .targetType(target)
                .targetId(UUID.randomUUID())
                .deadline(deadline)
                .occurredAt(occurred)
                .build();
    }

    @Test
    void acceptsAllMembershipNoticesWithServerText() {
        Instant end = Instant.parse("2030-01-12T15:00:00Z");
        for (String type :
                new String[] {
                    "MEMBERSHIP_PAYMENT_FAILED",
                    "MEMBERSHIP_RENEWED",
                    "MEMBERSHIP_EXPIRING",
                    "MEMBERSHIP_EXPIRED"
                }) {
            var e = notice(type, "MEMBERSHIP", end);
            assertThatCode(() -> policy.validate(e)).doesNotThrowAnyException();
            assertThat(policy.content(e).title()).startsWith("멤버십");
        }
        assertThat(policy.content(notice("MEMBERSHIP_EXPIRING", "MEMBERSHIP", end)).body())
                .contains("2030.01.13 00:00");
        // 만료 예정 푸시는 혜택 종료 뒤에 보내지 않습니다.
        assertThat(policy.pushDeadline(notice("MEMBERSHIP_EXPIRING", "MEMBERSHIP", end)))
                .isEqualTo(end);
    }

    @Test
    void rejectsWrongTargetOrMissingDeadline() {
        assertThatThrownBy(() -> policy.validate(notice("MEMBERSHIP_RENEWED", "ORDER", null)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.validate(notice("MEMBERSHIP_EXPIRING", "MEMBERSHIP", null)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> policy.validate(notice("SELLER_APPROVED", "MEMBERSHIP", null)))
                .isInstanceOf(RuntimeException.class);
    }
}
