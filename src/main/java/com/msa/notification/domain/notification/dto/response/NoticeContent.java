package com.msa.notification.domain.notification.dto.response;

/** 내부 렌더링 결과입니다. HTML과 외부 이동 URL을 포함하지 않습니다. */
@lombok.Builder
public record NoticeContent(String title, String body) {}
