-- 알림 서비스의 최초 테이블입니다. 엔티티 enum 값은 애플리케이션이 검증하므로 DB CHECK로 고정하지 않습니다.

create table notification.delivery_work (
    attempts integer not null,
    http_status integer,
    expires_at timestamp(6) with time zone not null,
    generation bigint not null,
    lease_until timestamp(6) with time zone,
    next_at timestamp(6) with time zone not null,
    event_id uuid not null,
    id uuid not null,
    lease_token uuid,
    subscription_key uuid not null,
    kind varchar(255) not null,
    payload text not null,
    status varchar(255) not null,
    primary key (id),
    unique (event_id, kind, subscription_key)
);

create table notification.inbox_message (
    created_at timestamp(6) with time zone not null,
    deadline timestamp(6) with time zone,
    occurred_at timestamp(6) with time zone,
    read_at timestamp(6) with time zone,
    account_id uuid not null,
    event_id uuid not null,
    id uuid not null,
    product_id uuid not null,
    related_id uuid,
    title varchar(120) not null,
    body varchar(500) not null,
    notification_type varchar(255),
    target_type varchar(255),
    primary key (id),
    unique (event_id, account_id)
);

create table notification.notice_cursor (
    latest_version bigint not null,
    id varchar(120) not null,
    primary key (id)
);

create table notification.push_subscription (
    active boolean not null,
    created_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone,
    generation bigint not null,
    account_id uuid not null,
    id uuid not null,
    endpoint_hash varchar(64) not null unique,
    credentials text,
    primary key (id)
);

create table notification.subscriber_guard (
    id uuid not null,
    primary key (id)
);

create index idx_delivery_due
   on notification.delivery_work (status, next_at);

create index idx_inbox_account_created
   on notification.inbox_message (account_id, created_at, id);

create index idx_push_subscription_account
   on notification.push_subscription (account_id);
