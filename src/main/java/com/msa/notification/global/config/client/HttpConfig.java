package com.msa.notification.global.config.client;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** 내부 HTTP도 연결/요청 시간을 제한하며 redirect로 인증 정보가 유출되지 않게 합니다. */
@Configuration
public class HttpConfig {
    /**
     * 내부 서비스 호출 전용입니다. Spring Cloud LoadBalancer 인터셉터가 서비스 ID를 Eureka 인스턴스로 바꾸고 상태 확인과 연결 실패
     * 재시도를 적용합니다. RestClient.Builder를 빈으로 두면 Eureka 클라이언트도 그 Builder로 레지스트리를 조회하다 막히므로 인터셉터는
     * 이 클라이언트에만 붙입니다. 서비스 토큰과 동의 정보가 오가므로 운영은 https만 허용하고 로컬·테스트만 http를 허용합니다.
     */
    @Bean
    public RestClient loadBalancedRestClient(
            ClientProperties properties,
            Environment environment,
            ObjectProvider<BlockingLoadBalancerInterceptor> interceptor) {
        boolean local =
                environment.acceptsProfiles(Profiles.of("local | test"))
                        && !environment.acceptsProfiles(Profiles.of("prod"));
        if (!"https".equals(properties.scheme())
                && !(local && "http".equals(properties.scheme())))
            throw new IllegalArgumentException("내부 서비스 주소의 전송 설정이 올바르지 않습니다.");
        return RestClient.builder()
                .requestFactory(requestFactory())
                .requestInterceptor(new DeferringLoadBalancerInterceptor(interceptor))
                .build();
    }

    /** LoadBalancer 상태 확인이 실제 인스턴스 주소를 직접 호출할 때 쓰는 일반 클라이언트입니다. 서비스 ID 변환을 거치지 않습니다. */
    @Bean
    @Primary
    public RestClient healthCheckRestClient() {
        return RestClient.builder().requestFactory(requestFactory()).build();
    }

    /** 연결 3초·응답 5초 제한이며 리다이렉트를 따라가지 않습니다. */
    public static JdkClientHttpRequestFactory requestFactory() {
        var http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }
}
