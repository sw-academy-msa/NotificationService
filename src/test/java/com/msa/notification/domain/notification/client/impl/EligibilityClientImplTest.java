package com.msa.notification.domain.notification.client.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.dto.response.Candidate;
import com.msa.notification.global.config.client.ClientProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 로컬 HTTP 대역으로 명확한 거절과 장애를 구분합니다. */
class EligibilityClientImplTest {
    HttpServer server;
    String base;
    ServiceTokenProvider tokens;
    EligibilityClient client;
    Candidate event;
    AtomicInteger status;

    @BeforeEach
    void start() throws Exception {
        status = new AtomicInteger(200);
        tokens = mock(ServiceTokenProvider.class);
        when(tokens.token()).thenReturn("synthetic-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        event =
                new Candidate(
                        UUID.randomUUID(),
                        1,
                        "PERSONALIZED_PRODUCT_CANDIDATE",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "상품",
                        "C",
                        "분류",
                        Instant.now(),
                        Instant.now(),
                        Instant.now().plusSeconds(600),
                        LocalDate.now(),
                        Instant.now());
        server.createContext(
                "/",
                exchange -> {
                    int code = status.get();
                    String path = exchange.getRequestURI().getPath();
                    String data =
                            path.contains("notification-eligibility")
                                    ? "{\"eligible\":true}"
                                    : path.contains("personalization")
                                            ? "{\"analyticsEnabled\":true}"
                                            : "{\"id\":\""
                                                    + event.productId()
                                                    + "\",\"name\":\"current"
                                                    + " product\",\"status\":\"ON_SALE\",\"endsAt\":\""
                                                    + event.endsAt()
                                                    + "\"}";
                    byte[] body =
                            ("{\"success\":true,\"data\":" + data + "}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(code, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client =
                new EligibilityClientImpl(
                        HttpClient.newHttpClient(),
                        new ClientProperties(base, base, base, "", ""),
                        tokens,
                        JsonMapper.builder().build(),
                        Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsEligibilityAndCurrentProductThroughHttp() {
        assertThat(client.eligibleProductName(event)).isEqualTo("current product");
    }

    @Test
    void authorizationFailureInvalidatesTokenAndRetriesRatherThanOptingOut() {
        status.set(401);
        assertThatThrownBy(() -> client.eligibleProductName(event))
                .isInstanceOf(RemoteUnavailable.class);
        verify(tokens).invalidate();
    }

    @Test
    void upstreamFailureIsNotInterpretedAsConsentDenial() {
        status.set(503);
        assertThatThrownBy(() -> client.eligibleProductName(event))
                .isInstanceOf(RemoteUnavailable.class);
    }
}
