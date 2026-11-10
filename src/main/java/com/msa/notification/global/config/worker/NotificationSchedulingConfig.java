package com.msa.notification.global.config.worker;

import com.msa.notification.domain.notification.service.NotificationWorker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 자동 실행은 명시적으로 켜며 모든 복제본이 DB lease로 경쟁합니다. */
@Configuration
@ConditionalOnProperty(name = "notification.worker-enabled", havingValue = "true")
public class NotificationSchedulingConfig {
    private final NotificationWorker worker;

    public NotificationSchedulingConfig(NotificationWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${notification.poll-ms:1000}")
    public void publish() {
        worker.runNext();
    }
}
