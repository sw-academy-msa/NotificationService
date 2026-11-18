-- 상품 알림 신청과 알림 판단용 상품 상태입니다. 신청자에게 판매 시작·가격 인하·수량 임박 알림을 보냅니다.

create table notification.product_alert_subscription (
    created_at timestamp(6) with time zone not null,
    account_id uuid not null,
    id uuid not null,
    product_id uuid not null,
    primary key (id),
    unique (account_id, product_id)
);

-- 상품 공개 이벤트의 최신 버전만 반영합니다. 알림을 보낸 기준값을 저장해 같은 알림을 반복하지 않습니다.
create table notification.product_alert_target (
    low_stock_noticed boolean not null,
    price bigint not null,
    start_noticed boolean not null,
    ends_at timestamp(6) with time zone not null,
    starts_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    version bigint not null,
    product_id uuid not null,
    seller_id uuid not null,
    primary key (product_id)
);

create index idx_product_alert_subscription_product
   on notification.product_alert_subscription (product_id);

create index idx_product_alert_target_start
   on notification.product_alert_target (start_noticed, starts_at);
