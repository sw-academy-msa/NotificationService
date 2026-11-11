package com.msa.notification.domain.notification.client.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.global.config.client.ClientProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 내부 호출이 고정 주소 없이 서비스 ID 주소로 나가고 LoadBalancer가 고른 인스턴스로 전달되는지 실제 HTTP fixture로 확인합니다. */
class InternalHttpImplTest {
    final List<HttpServer> servers = new ArrayList<>();
    FixedLoadBalancer balancer;
    InternalHttpImpl http;

    @BeforeEach
    void setup() {
        balancer = new FixedLoadBalancer(Map.of());
        http =
                new InternalHttpImpl(
                        balancer.restClientBuilder().build(),
                        new ClientProperties(
                                "http", "account-service", "ai-service", "pq-service", "", ""));
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    @Test
    void unregisteredServiceIsRetrySignalNotFixedAddressFallback() {
        assertThatThrownBy(() -> http.send("account-service", "GET", "/x", Map.of(), null))
                .isInstanceOf(RemoteUnavailable.class);
    }

    @Test
    void choosesRegisteredInstanceOnEveryRequest() {
        balancer.register("account-service", start("replica-a"));
        assertThat(http.send("account-service", "GET", "/x?accountId=1", Map.of(), null).body())
                .isEqualTo("replica-a /x?accountId=1");
        // 첫 인스턴스가 목록에서 빠지고 복제본이 남은 상황입니다. 다음 요청은 재시작 없이 복제본으로 갑니다.
        balancer.register("account-service", start("replica-b"));
        assertThat(http.send("account-service", "GET", "/x?accountId=1", Map.of(), null).body())
                .isEqualTo("replica-b /x?accountId=1");
    }

    @Test
    void returnsStatusWithoutTreatingItAsTransportFailure() {
        balancer.register("ai-service", start("ai"));
        // 응답을 받은 오류(401 등)는 호출 측이 토큰 무효화 등으로 해석하도록 그대로 돌려줍니다.
        assertThat(http.send("ai-service", "GET", "/status/401", Map.of(), null).status())
                .isEqualTo(401);
    }

    private String start(String name) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    e -> {
                        String path = e.getRequestURI().toString();
                        int code = path.startsWith("/status/") ? Integer.parseInt(path.substring(8)) : 200;
                        byte[] b = (name + " " + path).getBytes(StandardCharsets.UTF_8);
                        e.sendResponseHeaders(code, b.length);
                        e.getResponseBody().write(b);
                        e.close();
                    });
            server.start();
            servers.add(server);
            return "http://127.0.0.1:" + server.getAddress().getPort();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
