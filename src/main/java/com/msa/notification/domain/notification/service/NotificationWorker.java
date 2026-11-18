package com.msa.notification.domain.notification.service;

/** 한 번의 작업은 한 영속 lease를 처리하며 스케줄러와 테스트가 같은 경로를 사용합니다. */
public interface NotificationWorker {
    void runNext();
}
