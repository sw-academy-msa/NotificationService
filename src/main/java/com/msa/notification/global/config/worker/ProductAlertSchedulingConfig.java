package com.msa.notification.global.config.worker;

import com.msa.notification.domain.notification.service.ProductAlertService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** 판매 시작 알림 예약과 끝난 상품 정리를 실행합니다. 여러 인스턴스는 행 잠금으로 겹치지 않게 처리합니다. */
@Configuration
@ConditionalOnProperty(name = "notification.product-alert-enabled", havingValue = "true")
public class ProductAlertSchedulingConfig {
    private final ProductAlertService service;

    public ProductAlertSchedulingConfig(ProductAlertService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${notification.product-alert.start-poll-ms:30000}")
    public void starts() {
        service.dispatchStarts();
    }

    @Scheduled(fixedDelayString = "${notification.product-alert.cleanup-poll-ms:3600000}")
    public void cleanup() {
        service.cleanup();
    }
}
