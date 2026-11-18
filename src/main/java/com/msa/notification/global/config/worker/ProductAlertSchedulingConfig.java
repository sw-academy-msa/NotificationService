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
        // 한 묶음이 가득 찼으면 같은 폴링에서 다음 묶음을 이어서 처리합니다(묶음마다 별도 트랜잭션).
        // 시작 시각이 지난 상품은 대상에서 빠지므로 다음 폴링까지 기다리면 알림을 놓칠 수 있습니다.
        for (int i = 0; i < 100 && service.dispatchStarts() >= 50; i++) {}
    }

    @Scheduled(fixedDelayString = "${notification.product-alert.cleanup-poll-ms:3600000}")
    public void cleanup() {
        service.cleanup();
    }
}
