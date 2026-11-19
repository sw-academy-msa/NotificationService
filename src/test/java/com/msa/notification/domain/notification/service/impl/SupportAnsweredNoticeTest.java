package com.msa.notification.domain.notification.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.notification.domain.notification.dto.response.BusinessNotice;
import com.msa.notification.global.config.push.BusinessNoticeProperties;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;

/** Account의 1:1 문의 답변 알림을 받아 서버가 정한 문구로 보여 준다 */
class SupportAnsweredNoticeTest {
    private final BusinessNoticePolicyImpl policy =
            new BusinessNoticePolicyImpl(new BusinessNoticeProperties(Duration.ofDays(7)));

    private BusinessNotice notice(String targetType) {
        UUID account = UUID.randomUUID(), inquiry = UUID.randomUUID();
        String identity = "ACCOUNT:SUPPORT_ANSWERED:" + inquiry + ":1:" + account;
        return new BusinessNotice(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)), 1, "BUSINESS_NOTIFICATION",
                "ACCOUNT", account, "SUPPORT_ANSWERED", inquiry, 1, targetType, inquiry, null, null,
                Instant.parse("2026-11-20T00:00:00Z"));
    }

    @Test
    void acceptsSupportAnsweredWithSupportInquiryTarget() {
        var e = notice("SUPPORT_INQUIRY");
        assertThatCode(() -> policy.validate(e)).doesNotThrowAnyException();
        assertThat(policy.content(e).title()).isEqualTo("1:1 문의 답변");
    }

    @Test
    void rejectsWrongTarget() {
        assertThatThrownBy(() -> policy.validate(notice("ORDER"))).isInstanceOf(RuntimeException.class);
    }
}
