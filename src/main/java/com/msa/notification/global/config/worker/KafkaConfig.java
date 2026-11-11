package com.msa.notification.global.config.worker;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

/** 저장 실패를 건너뛰지 않으며 payload를 담는 기본 오류 로그를 사용하지 않습니다. */
@Configuration
public class KafkaConfig {
    @Bean
    public CommonErrorHandler notificationErrorHandler() {
        var handler =
                new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(java.util.Map.of(), true);
        handler.setRetryListeners(
                (record, exception, attempt) ->
                        org.slf4j.LoggerFactory.getLogger(KafkaConfig.class)
                                .warn(
                                        "notification input retry partition={} offset={} attempt={}"
                                                + " cause={}",
                                        record.partition(),
                                        record.offset(),
                                        attempt,
                                        exception.getClass().getSimpleName()));
        return handler;
    }
}
