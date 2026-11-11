package com.msa.notification.global.config.worker;

import com.msa.notification.global.config.client.ClientProperties;
import com.msa.notification.global.config.push.PushProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** 시간과 작업 설정을 공유하되 외부 통신 중 DB 잠금을 유지하지 않습니다. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
    PushProperties.class,
    ClientProperties.class,
    com.msa.notification.global.config.push.BusinessNoticeProperties.class
})
public class WorkerConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
