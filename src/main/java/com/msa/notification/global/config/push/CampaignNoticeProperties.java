package com.msa.notification.global.config.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/** 야간 시연 예외는 올바른 열 개 이하 허용 목록의 계정에만 적용합니다. */
@ConfigurationProperties("notification.campaign")
public record CampaignNoticeProperties(String mode, String demoAccountIds) {
    public boolean demoAllowed(UUID account) {
        if (!"DEMO".equals(mode) || demoAccountIds == null || demoAccountIds.isBlank())
            return false;
        try {
            var ids = new HashSet<UUID>();
            for (String value : demoAccountIds.split(",", -1))
                ids.add(UUID.fromString(value.trim()));
            return !ids.isEmpty() && ids.size() <= 10 && ids.contains(account);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "CampaignNoticeProperties[REDACTED]";
    }
}
