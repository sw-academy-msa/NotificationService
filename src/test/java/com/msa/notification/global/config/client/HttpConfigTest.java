package com.msa.notification.global.config.client;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.BlockingLoadBalancerInterceptor;
import org.springframework.mock.env.MockEnvironment;

/** 서비스 토큰과 동의 정보가 오가므로 운영은 https만, 로컬·테스트만 http를 허용하는지 시작 시점 검사를 확인합니다. */
class HttpConfigTest {
    // scheme 검사만 확인하므로 LoadBalancer 인터셉터는 요청 시점에만 찾는 빈 공급자로 둡니다.
    @SuppressWarnings("unchecked")
    static final ObjectProvider<BlockingLoadBalancerInterceptor> NO_INTERCEPTOR =
            org.mockito.Mockito.mock(ObjectProvider.class);

    @Test
    void productionRejectsPlaintextScheme() {
        assertThatThrownBy(
                        () ->
                                new HttpConfig()
                                        .loadBalancedRestClient(
                                                properties("http"), profile("prod"), NO_INTERCEPTOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                new HttpConfig()
                                        .loadBalancedRestClient(
                                                properties("https"), profile("prod"), NO_INTERCEPTOR))
                .doesNotThrowAnyException();
    }

    @Test
    void localProfileMayUsePlaintext() {
        assertThatCode(
                        () ->
                                new HttpConfig()
                                        .loadBalancedRestClient(
                                                properties("http"), profile("local"), NO_INTERCEPTOR))
                .doesNotThrowAnyException();
    }

    private static ClientProperties properties(String scheme) {
        return new ClientProperties(scheme, "account-service", "ai-service", "pq-service", "", "");
    }

    private static MockEnvironment profile(String name) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(name);
        return environment;
    }
}
