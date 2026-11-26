-- 푸시와 알림함이 같은 쿠폰 받기 코드로 이동하게 합니다.
ALTER TABLE notification.inbox_message ADD COLUMN campaign_code varchar(40);
