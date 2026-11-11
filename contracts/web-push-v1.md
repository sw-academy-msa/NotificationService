# iPhone PWA 웹 푸시 계약 v1

## 프런트가 준비할 것

iOS/iPadOS 16.4 이상 홈 화면 웹 앱을 대상으로 합니다. HTTPS 배포, standalone 또는 fullscreen manifest,
Service Worker가 필요합니다. Safari 탭만 연 상태를 수신 가능 상태로 취급하지 않습니다.
사용자의 알림 켜기 버튼 동작 안에서 권한을 요청하고 subscribe합니다.
공개 VAPID 키는 push-configuration에서 받고 private key는 서버만 관리합니다.
Apple 개발자 개인키나 Account RSA 개인키를 가져오지 않습니다.

subscribe 결과 toJSON()을 POST push-subscriptions로 전송합니다.
endpoint, expirationTime(epoch milliseconds 또는 null), keys.p256dh, keys.auth 형식입니다.
iPhone용 web.push.apple.com HTTPS endpoint만 허용합니다. 다른 브라우저 지원은 허용 목록과 테스트를 함께 확장해야 합니다.
서버 등록 응답의 id를 보관하고 로그아웃 시 DELETE로 해제한 뒤 브라우저 unsubscribe를 수행합니다.
다른 계정으로 endpoint 소유권을 자동 이전하지 않습니다.
POST 실패 시 활성화 완료로 표시하지 말고 재요청하거나 새 구독을 해제해 주세요.

푸시는 항상 사용자에게 표시합니다. silent push를 구현하지 않습니다.
푸시 클릭 URL은 동일 origin의 /?notificationId=UUID입니다.
프런트는 로그인 후 본인 알림 상세를 읽고 targetType=PRODUCT, targetId로 실제 상품 페이지를 선택합니다.
현재 React 라우트를 추정해 서버에 하드코딩하지 않습니다.
알림함 목록 조회나 푸시 서비스 접수는 읽음 처리하지 않습니다. 화면에서 읽었을 때 PATCH합니다.
예시 manifest의 name, id, start_url과 아이콘은 실제 프런트에 맞춰 구성해야 합니다.

## 영속 처리

Kafka 그룹 notification-personalized-products-v1은 동일 서비스 복제본끼리 공유합니다.
입력 eventId와 원본 payload를 먼저 영속화하고 commit합니다. 저장 실패는 ack하지 않습니다.
같은 eventId의 다른 payload는 충돌이며 자동 덮어쓰지 않습니다.
잘못된 이벤트는 건너뛰지 않으므로 해당 파티션은 운영 확인과 격리가 필요합니다.
Kafka 토픽 생성은 기존 AIService scripts/Initialize-LocalAiTopics.ps1을 사용합니다.

INTENT 작업은 Account 최신 eligible, AI analyticsEnabled, Product Query 공개 상품 상태를 확인합니다.
Account 내부 GET /internal/v1/notification-eligibility?accountId=UUID의 eligible는
ACTIVE BUYER, 개인화 알림 설정, 마케팅 동의를 모두 만족한 경우 true입니다.
Notification 서비스 토큰의 account.notifications.read scope와 지정 주체만 접근합니다.
AI에는 기존 ai.personalization.read scope로 별도 동의를 확인합니다.
Product Query 404는 조회 모델 지연일 수 있어 판매 종료 시각 전까지 재시도합니다.
SOLD_OUT은 공개 여부와 달라 단독으로 알림을 취소하지 않습니다. 실제 구매 가능 여부를 보증하지 않습니다.
종료되었거나 명확한 동의 거절이면 SKIPPED, HTTP 장애와 잘못된 응답은 RETRY입니다.
여러 서비스의 동의 변경과 외부 발송은 하나의 트랜잭션이 아니며 이미 접수된 푸시는 회수할 수 없습니다.

동의 확인 후 inbox와 당시 활성 구독별 PUSH 작업, INTENT 완료를 같은 notification DB 트랜잭션으로 저장합니다.
구독이 없어도 알림함은 남습니다. 신규 기기에 과거 알림을 자동 백필하지 않습니다.
계정별 활성 기기는 최대 20개로 제한해 무한 fanout을 방지합니다.
외부 HTTP는 DB 잠금 밖에서 호출하고 완료 시 120초 lease와 token을 다시 검사합니다.
claim은 SKIP LOCKED를 사용하며 만료된 lease는 다른 복제본이 재선점할 수 있습니다.
발송 직전에도 최신 자격을 다시 확인합니다. 후보의 원본 endsAt을 넘겨 재시도하지 않습니다.
일시 실패 지연은 10초부터 최대 1시간이며 Retry-After를 최대 1시간까지 반영합니다.

2xx는 SENT, 즉 Apple 푸시 서비스 접수이며 기기 수신이나 읽음이 아닙니다.
404/410은 해당 세대 구독만 비활성화합니다. 이미 갱신된 구독은 유지합니다.
401/403은 서버 인증 설정 문제로 재시도하며 사용자 구독을 지우지 않습니다.
429, 5xx, timeout과 불확실한 실패는 RETRY, 나머지 3xx/4xx는 FAILED입니다.
redirect를 따르지 않고 연결 5초, 요청 10초를 제한합니다.
TTL은 최대 300초와 원래 상품 endsAt까지의 잔여 시간 중 작은 값입니다.
처리 실패 로그는 작업 ID와 시도 횟수, 예외 종류만 남깁니다.

푸시 접수 후 DB 완료 전 장애에서는 중복 전송이 가능합니다.
notificationId를 tag와 Topic으로 재사용해 표시 중복을 줄이지만 exactly-once 전달을 보장하지 않습니다.
알림함의 유일 제약은 중복 알림 기록을 막습니다.
endpoint, p256dh, auth는 AES-256-GCM으로 암호화하고 계정 ID와 구독 ID를 AAD로 사용합니다.
향후 보관 기간, 삭제/비활성 계정의 오래된 알림 정리, 운영 지표와 백업 정책은 별도 확정해야 합니다.

## 확인한 공식 근거

- https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/
- https://webkit.org/blog/12945/meet-web-push/
- https://github.com/web-push-libs/webpush-java

현재 코드 테스트는 실제 Apple 단말 발송 검증을 대체하지 않습니다.
