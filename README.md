# STOCKDROP NotificationService

iPhone 홈 화면 PWA의 웹 푸시, 본인 알림함, 구독과 영속 재시도를 담당합니다.
Java 21, Spring Boot 4.1.1, Cloud 2025.1.4, MVC, JPA를 사용합니다.

## 실행

1. Account의 일반 PostgreSQL과 공용 Kafka를 실행합니다. Command DB를 사용하지 않습니다.
2. Java 21 환경에서 scripts/Initialize-LocalNotificationDatabase.ps1을 실행합니다.
   .env가 없으면 예시에서 새 비밀번호를 생성하고 기존 일반 인스턴스에 notification_service DB와 notification 계정, 스키마를 만듭니다.
   기존 역할 비밀번호는 바꾸지 않고 TCP 인증을 검사합니다. 컨테이너나 볼륨을 삭제하지 않습니다.
3. AccountService와 AIService의 기존 .env가 있는 상태에서 ./gradlew.bat prepareLocalNotification을 명시 실행합니다.
   Notification 전용 서비스 주체와 비밀값, BCrypt 해시를 기존 Account 발급 계약에 등록합니다.
   VAPID 키 쌍과 AES-256 저장 암호화 키도 Notification .env에 생성하고 콘솔에는 출력하지 않습니다.
   기존 키나 충돌하는 서비스 주체는 자동 교체하지 않습니다. 실행 중 세 파일을 동시에 편집하지 마세요.
4. VAPID_SUBJECT를 운영자 mailto 연락 주소로 설정합니다.
   NOTIFICATION_CONSUMER_ENABLED, NOTIFICATION_WORKER_ENABLED를 true로 설정합니다.
   실제 푸시를 사용할 때 WEB_PUSH_ENABLED=true로 설정합니다.
   AI의 기존 개인화 기능과 후보 Outbox도 활성화되어 있어야 합니다.
5. Account, AI, 일반 Gateway를 재시작하고 ./gradlew.bat bootRun으로 실행합니다.

기본 포트는 8087, Eureka 이름은 notification-service입니다. 일반 Gateway 8000을 이용합니다.
선택적인 compose.yaml은 앱만 실행하며 호스트 서비스는 Docker bridge 주소에서 접근 가능해야 합니다.
운영은 DB_URL을 일반 RDS로 주입하고 ddl-auto=validate를 사용합니다. 개발은 update입니다.
여러 복제본은 같은 DB, VAPID 키와 저장 암호화 키를 사용해야 합니다.
기존 암호화 키를 바꾸면 저장 구독을 읽을 수 없으므로 키 회전은 별도 절차가 필요합니다.

## API

모두 Bearer 사용자 JWT와 BUYER, SELLER, ADMIN 중 역할이 필요합니다. accountId는 JWT sub에서 결정합니다.

| 메서드 | 경로 | 목적 |
|---|---|---|
| GET | /api/v1/notifications | 본인 알림 목록, page와 size |
| GET | /api/v1/notifications/{id} | 본인 알림 상세와 이동 대상 |
| GET | /api/v1/notifications/unread-count | 안 읽은 개수 |
| PATCH | /api/v1/notifications/{id}/read | 최초 읽음 기록 |
| PATCH | /api/v1/notifications/read-all | 현재 시각까지 일괄 읽음 |
| GET | /api/v1/notifications/push-configuration | 공개 VAPID 키와 활성 여부 |
| POST | /api/v1/notifications/push-subscriptions | 브라우저 구독 등록과 갱신 |
| GET | /api/v1/notifications/push-subscriptions | 본인 기기 구독 목록, 비밀값 제외 |
| DELETE | /api/v1/notifications/push-subscriptions/{id} | 본인 구독 비활성화 |

HTTP 응답은 ApiResponse와 DTO이며 구독 endpoint, auth, 개인키는 반환하지 않습니다.
실행 후 Swagger는 로컬 /swagger-ui/index.html에서 확인합니다. 운영에서는 닫혀 있습니다.

## 연결과 검증 범위

기존 AI의 stockdrop.ai.recommendation-candidates.v1과 일반 심사/주문/배송 전용 세 토픽을 지원합니다.
일반 알림은 별도 소비 활성화 플래그가 필요하며 광고/분석 동의와 분리합니다.
별도 공개 발송 API는 없습니다.
테스트 명령은 ./gradlew.bat test bootJar이며 Docker가 필요합니다.
격리 PostgreSQL, Kafka와 합성 키를 사용하며 실제 Apple 전송이나 AWS 배포를 수행하지 않습니다.

## 문서

- [웹 푸시와 영속 작업 계약](contracts/web-push-v1.md)
- [프런트 Service Worker 예시](contracts/examples/notification-sw.js)
- [Manifest 예시](contracts/examples/manifest.webmanifest)

## 일반 업무 알림

[심사 결과, 보완 요청과 기한, 주문 확정과 배송 알림 계약](contracts/business-notifications-v1.md)을 확인합니다.
상태 변경과 Outbox는 함께 커밋하며 기존 구매 Saga와 결제 Kafka 계약은 유지합니다.
