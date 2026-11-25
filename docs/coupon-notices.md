# 쿠폰과 캠페인 광고 알림

NOTIFICATION_COUPON_CONSUMER_ENABLED=false가 기본입니다. 새 토픽 stockdrop.notifications.coupon.v1과 stockdrop.notifications.campaign.v1을 별도 그룹 notification-coupon-v1, notification-campaign-v1으로 소비합니다. 기존 BusinessNotice의 eventId, 원본, target, deadline을 검증하고 기존 WorkStore, 알림함, DeliveryWork를 재사용합니다.

새 consumer는 16KiB 제한, 계약 오류 즉시 DLT, 일시 장애 1초 간격 3회 후 같은 partition DLT입니다. DLT 발행 ack가 실패하면 해당 listener를 멈추고 offset을 넘기지 않습니다. 기존 업무 알림의 재시도 정책은 변경하지 않습니다.

쿠폰과 캠페인 원본은 Account PERSONALIZED 동의 및 AI 분석 참여를 알림함 생성과 실제 푸시 직전에 확인합니다. 상품 target은 ProductQuery의 현재 공개 상품명과 category 및 stock.availableQuantity를 사용합니다. ENDED와 판매 종료가 지난 상품은 건너뛰고 결제 재안내 및 반복 조회는 SOLD_OUT도 건너뜁니다. 잔여 수량은 0이면 보내지 않습니다. SALE_OPEN은 현재 product_alert_subscription도 확인합니다.

규칙 여덟 종류의 서버 고정 문구와 예산 소진 CAMPAIGN 대체 문구에 (광고) 제목을 사용합니다. 상품명과 카테고리는 30자로 자르고 검색어, 금액, 개인정보를 복제하지 않습니다. COUPON target은 FE /my/coupons 이동을 위한 계약입니다.

21:00~08:00 KST는 알림 작업을 재시도 상태로 미룹니다. 기한이 지나면 건너뜁니다. AI_CAMPAIGN_MODE=DEMO이면서 CAMPAIGN_DEMO_ACCOUNT_IDS에 포함된 올바른 UUID 계정만 야간 예외입니다. 빈 목록, 잘못된 UUID 또는 10개 초과 목록은 예외를 허용하지 않습니다. 광고 표시는 시연에서도 유지합니다.

배포 순서: Infra 원본 토픽 및 DLT 준비, Notification 코드 배포와 AI 계정별 분석 동의 조회 API 배포, Account 권한, CouponService 준비 후 새 consumer를 켭니다. 기존 NOTIFICATION_WORKER_ENABLED와 Web Push 플래그 및 서비스 자격 증명도 필요합니다. 배포만으로 새 토픽 소비를 시작하지 않습니다.

되돌리기: NOTIFICATION_COUPON_CONSUMER_ENABLED=false로 새 입력을 멈추고 AI_CAMPAIGN_MODE=OFF 및 캠페인 pause로 생산을 멈춥니다. 같은 플래그를 끄면 이미 저장된 광고 작업도 보류하고 마감이 지나면 건너뜁니다. 동의도 계속 확인합니다. 토픽, DLT, 알림함, 영속 작업을 삭제하지 않습니다. 코드 복구는 develop 머지 커밋 revert PR입니다. FE COUPON target 이동, 쿠폰함 및 실제 브라우저 Push E2E는 별도입니다.
