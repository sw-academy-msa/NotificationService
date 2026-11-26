package com.msa.notification.domain.notification.service;

import com.msa.notification.domain.notification.dto.response.ClaimCampaignNotice;

/** 캠페인 요청을 계정별 멱등 업무 알림으로 나눕니다. */
public interface ClaimCampaignFanout {
    void accept(ClaimCampaignNotice event, String key);
}
