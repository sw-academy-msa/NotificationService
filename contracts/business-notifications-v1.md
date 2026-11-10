# 일반 업무 알림 Kafka 계약 v1

## 범위와 수신자

| 원본 | notificationType | 수신자 | targetType |
|---|---|---|---|
| Account | SELLER_APPROVED, SELLER_REJECTED | 판매자 신청자, 재심사 포함 | SELLER_APPLICATION |
| Account | SELLER_CORRECTION_REQUESTED, SELLER_CORRECTION_EXPIRED | 보완 요청 대상 신청자 | SELLER_APPLICATION |
| Product | PRODUCT_APPROVED, PRODUCT_REJECTED | 상품 소유 판매자 | SELLER_PRODUCT |
| Product | PRODUCT_REVISION_APPROVED, PRODUCT_REVISION_REJECTED | 수정 심사 상품의 소유 판매자 | SELLER_PRODUCT_REVISION |
| Order | ORDER_CONFIRMED | 구매자와 판매자, 동일 계정이면 한 번 | ORDER, SELLER_ORDER |
| Order | ORDER_SHIPPED, ORDER_DELIVERED | 해당 주문 구매자 | ORDER |

ORDER_CONFIRMED는 토스 승인이나 임시 확보 시점이 아니라 Saga가 재고 확정까지 마친 시점입니다.
배송 완료는 현재 판매자가 수동으로 등록한 상태라는 문구를 사용하며 택배사 도착을 확인했다고 표현하지 않습니다.
송장 정정, 주소 변경, Saga 재시도와 중간 결제 상태에는 알림을 만들지 않습니다.
보완 요청은 기한과 항목 확인 안내, 기한 경과는 실제 EXPIRED 전이에 연결합니다. 마감 임박 예약 알림은 추가하지 않습니다.
기존 이력의 판매자 스냅샷이 없으면 수신자를 임의 추정하거나 백필하지 않습니다.

## 이벤트와 데이터 소유권

전용 토픽은 stockdrop.notifications.account.v1, stockdrop.notifications.product.v1, stockdrop.notifications.order.v1입니다.
키는 aggregateId UUID이며 업무 DB 변경과 해당 서비스의 Outbox 저장은 같은 로컬 트랜잭션입니다.
Account는 account.notification_outbox를 새로 소유하고 기존 Product/Order는 자기 Outbox와 발행 작업을 재사용합니다.
별도 Outbox 서버와 공유 DB 트랜잭션을 만들지 않습니다. Account에 JWT 개인키를 복제하거나 인증 발급 계약을 바꾸지 않습니다.

필드는 eventId, schemaVersion=1, type=BUSINESS_NOTIFICATION, source(ACCOUNT/PRODUCT/ORDER), accountId,
notificationType, aggregateId, aggregateVersion, targetType, targetId, relatedId, deadline, occurredAt입니다.
eventId는 UTF-8 문자열 source:notificationType:aggregateId:aggregateVersion:accountId의 UUID.nameUUIDFromBytes입니다.
재시도는 최초 ID, 발생 시각과 본문을 유지합니다. 동일 업무 버전/수신자 이벤트를 덮어쓰지 않습니다.
version은 Account 신청 원본, Product/수정 심사 원본의 JPA 버전, Order projectionVersion입니다.
개인정보가 포함된 기존 Query 토픽을 Notification이 직접 소비하지 않습니다.
이벤트와 푸시에 심사 사유, 보완 항목 원문, PDF/S3 키, 연락처, 배송지, 송장 번호, 결제키를 넣지 않습니다.

알림 소비 그룹은 notification-business-v1이며 같은 서비스 복제본은 같은 그룹을 사용합니다.
topic과 source, partition key, 종류별 targetType과 고정 eventId를 검증합니다.
입력과 수신자/원본의 최대 버전을 Notification DB에 함께 저장한 뒤 Kafka offset을 진행합니다.
동일 ID의 다른 본문은 덮어쓰지 않고 재시도하며 잘못된 계약은 운영 격리가 필요합니다.
Outbox 발행 완료는 알림함 생성, 단말 수신이나 읽음 완료를 뜻하지 않습니다.

## 알림함, 동의와 푸시

일반 업무 알림은 마케팅 수신 동의와 GA 분석 참여, 개인화 추천 설정을 요구하지 않습니다.
Notification은 기존 내부 Account API에 purpose=TRANSACTIONAL을 전달해 최신 ACTIVE/BUYER 상태만 확인합니다.
가입자의 기본 BUYER와 판매자의 BUYER 유지 계약을 사용하며 탈퇴/정지된 수신자는 제외합니다.
기존 purpose 생략/PERSONALIZED와 맞춤 상품의 Account+AI+Query 확인은 유지합니다.
내부 수신 자격 API는 기존 Notification 서비스 JWT와 account.notifications.read scope로 보호합니다.

알림함 생성과 활성 기기별 푸시 작업 저장은 Notification 로컬 트랜잭션입니다.
알림함은 전송 기한이 지났다는 이유로 버리지 않으며 기기 구독이 없어도 저장합니다.
일반 작업의 intent 만료 상한 9999년은 기술적 저장 표현이며 보관 기간 정책을 의미하지 않습니다.
푸시 재시도 상한은 notification.business.push-max-age, 기본 7일이며 0초 초과 30일 이하로 설정합니다.
보완 요청 푸시는 제출 기한과 위 상한 중 빠른 시각까지만 시도합니다. 알림함의 과거 요청 이력은 유지합니다.
같은 수신자/원본의 이전 버전은 이력으로 저장하되 더 최신 버전이 도착한 뒤 이전 푸시를 새로 발송하지 않습니다.
이미 외부 푸시 서비스에 접수된 요청은 회수할 수 없고 여러 시스템의 상태 확인과 전송은 하나의 트랜잭션이 아닙니다.

기존 DB lease와 활성 구독/세대 확인, 계정 상태 재확인, 404/410 해제, 429/5xx/불확실 응답 재시도와 안정된 tag/Topic을 재사용합니다.
2xx는 푸시 서비스 접수이며 기기 수신과 읽음이 아닙니다. exactly-once 전달은 보장하지 않습니다.
Notification 기존 inbox_message.product_id 물리 컬럼은 데이터 이전 없이 일반 targetId로 매핑합니다.
서비스 간 상품 FK가 아니며 기존 맞춤 상품 행은 그대로 읽습니다. 새 nullable metadata 컬럼과 notice_cursor만 추가합니다.

## 프런트 계약

기존 GET /api/v1/notifications와 단건/읽음/구독 API를 그대로 사용합니다.
Message.type은 알림 종류, targetType/targetId는 화면과 원본 ID입니다.
relatedId는 수정 심사 ID 또는 보완 요청 ID, deadline은 보완 제출 기한 UTC이며 없으면 null입니다. occurredAt은 원래 발생 시각, createdAt은 알림함 수신 시각입니다.
SELLER_PRODUCT_REVISION은 targetId=productId, relatedId=revisionId입니다.
새로고침이나 알림 목록 GET을 읽음 처리하지 않고 실제 확인 시 PATCH를 사용합니다.
푸시 클릭은 기존 동일 origin /?notificationId=UUID를 사용합니다.
프런트는 로그인 후 본인 알림 상세를 읽고 실제 React 경로로 이동합니다. 알림 이벤트에 임의 URL을 담지 않습니다.
심사 사유/보완 항목/배송 정보는 해당 원본 API에서 권한 확인 후 조회합니다.
백엔드에 프런트 라우터나 iPhone Service Worker를 자동 설치했다고 표현하지 않습니다.

## 실행과 검증

공유 Kafka 실행 후 NotificationService/scripts/Initialize-LocalBusinessNotificationTopics.ps1을 명시 실행합니다.
Account ACCOUNT_OUTBOX_PUBLISHER_ENABLED=true, Product PRODUCT_OUTBOX_PUBLISHER_ENABLED=true,
Order ORDER_OUTBOX_PUBLISHER_ENABLED=true, Notification NOTIFICATION_BUSINESS_CONSUMER_ENABLED=true와 NOTIFICATION_WORKER_ENABLED=true가 필요합니다.
실제 푸시는 WEB_PUSH_ENABLED와 VAPID/저장 암호화 키가 별도로 필요하며 알림함 검증에 실제 Apple 호출은 필요하지 않습니다.
Account/Notification의 기존 서비스 인증 등록은 NotificationService ./gradlew.bat prepareLocalNotification으로 준비합니다.
실제 .env 값을 문서에 넣지 않으며 새 일반 알림 활성화 플래그는 기본 false입니다.
설정 변경 후 관련 앱을 재시작해야 합니다. 운영 스키마는 validate이므로 새 엔티티/컬럼 구조를 배포 전에 따로 준비합니다.
로컬 Compose 앱은 공용 Kafka INTERNAL listener kafka:9092에 연결하며 PostgreSQL과 Kafka 서버를 새로 중복 생성하지 않습니다.

검증은 격리 PostgreSQL/Kafka, 합성 이벤트와 HTTP 대역을 사용합니다.
Kafka 중단/복구 후 Account 자동 발행, 세 도메인 토픽 중복 수신, 역순, 일반/개인화 수신 목적 분리와 원자성을 확인합니다.
실제 단말 푸시, 실제 사용자 알림 발송, AWS 배포와 운영 ACL/TLS/SASL 적용은 별도입니다.
