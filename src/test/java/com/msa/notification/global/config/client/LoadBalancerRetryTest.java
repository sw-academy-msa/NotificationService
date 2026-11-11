package com.msa.notification.global.config.client;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.*;

import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

/** application.yaml의 실제 재시도 설정으로, 죽은 Account 인스턴스가 목록에 남아 있어도 연결 실패 요청이 살아 있는 인스턴스로 넘어가는지 확인합니다. */
class LoadBalancerRetryTest {
    static final String[] AUTO_CONFIGURATIONS = {
        "org.springframework.cloud.commons.util.UtilAutoConfiguration",
        "org.springframework.cloud.client.CommonsClientAutoConfiguration",
        "org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration",
        "org.springframework.cloud.client.discovery.composite.CompositeDiscoveryClientAutoConfiguration",
        "org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration",
        "org.springframework.cloud.client.loadbalancer.LoadBalancerDefaultMappingsProviderAutoConfiguration",
        "org.springframework.cloud.loadbalancer.config.LoadBalancerAutoConfiguration",
        "org.springframework.cloud.loadbalancer.config.BlockingLoadBalancerClientAutoConfiguration",
        "org.springframework.cloud.loadbalancer.config.LoadBalancerCacheAutoConfiguration"
    };

    HttpServer live;
    AtomicInteger hits;
    int deadPort;

    @BeforeEach
    void start() throws Exception {
        hits = new AtomicInteger();
        live = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        live.createContext(
                "/probe",
                e -> {
                    hits.incrementAndGet();
                    e.sendResponseHeaders(200, -1);
                    e.close();
                });
        live.start();
        // 열었다 닫은 포트는 연결이 거부됩니다. 하트비트가 끊겼지만 아직 목록에 남은 죽은 인스턴스를 흉내 냅니다.
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }
    }

    @AfterEach
    void stop() {
        live.stop(0);
    }

    @Test
    void connectionFailureIsRetriedOnAnotherInstanceIncludingTokenPost() {
        runner().run(
                        context -> {
                            var rest = context.getBean("loadBalancedRestClient", RestClient.class);
                            for (int i = 0; i < 6; i++) {
                                rest.post()
                                        .uri("http://account-service/probe")
                                        .retrieve()
                                        .toBodilessEntity();
                            }
                            assertThat(hits).hasValue(6);
                        });
    }

    @Test
    void withoutRetryDeadInstanceStillReceivesTraffic() {
        // 대조군입니다. 재시도를 끄면 같은 조건에서 죽은 인스턴스로 간 요청이 실패해야 위 테스트가 의미를 가집니다.
        runner().withPropertyValues("spring.cloud.loadbalancer.retry.enabled=false")
                .run(
                        context -> {
                            var rest = context.getBean("loadBalancedRestClient", RestClient.class);
                            int failures = 0;
                            for (int i = 0; i < 6; i++) {
                                try {
                                    rest.post()
                                            .uri("http://account-service/probe")
                                            .retrieve()
                                            .toBodilessEntity();
                                } catch (ResourceAccessException expected) {
                                    failures++;
                                }
                            }
                            assertThat(failures).isPositive();
                        });
    }

    @Test
    void doesNotExposeRestClientBuilderToFrameworkClients() {
        // Eureka 클라이언트는 컨텍스트의 RestClient.Builder로 레지스트리를 조회합니다. LoadBalancer가 붙은 Builder가 빈으로 노출되면
        // Eureka 서버 주소까지 서비스 ID로 해석하려다 기동이 멈추므로, 내부 호출 설정은 Builder 빈을 만들지 않아야 합니다.
        runner().run(context -> assertThat(context.getBeansOfType(RestClient.Builder.class)).isEmpty());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(classes()))
                .withUserConfiguration(HttpConfig.class, Properties.class)
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "spring.cloud.discovery.client.simple.instances.account-service[0].uri=http://127.0.0.1:"
                                + deadPort,
                        "spring.cloud.discovery.client.simple.instances.account-service[1].uri=http://127.0.0.1:"
                                + live.getAddress().getPort());
    }

    private static Class<?>[] classes() {
        try {
            var classes = new Class<?>[AUTO_CONFIGURATIONS.length];
            for (int i = 0; i < classes.length; i++) {
                classes[i] = Class.forName(AUTO_CONFIGURATIONS[i]);
            }
            return classes;
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** HttpConfig가 scheme 검사를 위해 ClientProperties를 주입받으므로 테스트 컨텍스트에도 등록합니다. */
    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            ClientProperties.class)
    static class Properties {}
}
