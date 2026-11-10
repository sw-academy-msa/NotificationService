package com.msa.notification.domain.notification.client;

import com.msa.notification.domain.notification.dto.response.Candidate;

/** null은 명확한 수신 거절, 예외는 확인 불가로 구분합니다. 반환 이름은 내부 DTO용 값입니다. */
public interface EligibilityClient {
    String eligibleProductName(Candidate candidate);

    /** 일반 업무 알림은 Account의 최신 활성 상태만 확인합니다. */
    boolean transactionalAllowed(java.util.UUID account);
}
