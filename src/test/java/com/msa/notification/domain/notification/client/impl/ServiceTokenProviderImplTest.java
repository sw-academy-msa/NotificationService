package com.msa.notification.domain.notification.client.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.global.config.client.ClientProperties;
import com.msa.notification.global.config.security.AuthProperties;
import com.msa.notification.global.config.security.JwtConfig;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 기존 Account 계약의 소수 초 만료 응답, 실제 서명, 캐시와 만료 후 실패를 검증합니다. */
class ServiceTokenProviderImplTest {
    @Test
    void reusesExistingContractAndNeverReturnsExpiredToken() throws Exception {
        var key = new RSAKeyGenerator(2048).keyID("synthetic-key").generate();
        var mapper = JsonMapper.builder().build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var count = new AtomicInteger();
        var status = new AtomicInteger(200);
        Instant now = Instant.parse("2030-01-01T00:00:00.123Z");
        UUID subject = UUID.randomUUID();
        String scope = "account.notifications.read ai.personalization.read";
        var signed =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        new JWTClaimsSet.Builder()
                                .issuer("http://localhost:8080")
                                .audience("stockdrop-api")
                                .subject(subject.toString())
                                .expirationTime(Date.from(now.plusSeconds(300)))
                                .claim("token_use", "access")
                                .claim("principal_type", "service")
                                .claim("client_id", "notification-service")
                                .claim("roles", List.of())
                                .claim("scope", scope)
                                .build());
        signed.sign(new RSASSASigner(key));
        server.createContext(
                "/jwks",
                e -> {
                    byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes();
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(200, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        server.createContext(
                "/internal/v1/auth/service-tokens",
                e -> {
                    count.incrementAndGet();
                    String basic =
                            "Basic "
                                    + Base64.getEncoder()
                                            .encodeToString(
                                                    "notification-service:synthetic-secret"
                                                            .getBytes());
                    boolean valid =
                            basic.equals(e.getRequestHeaders().getFirst("Authorization"))
                                    && new String(e.getRequestBody().readAllBytes())
                                            .contains(
                                                    "scope=account.notifications.read%20ai.personalization.read");
                    int code = valid ? status.get() : 401;
                    byte[] body =
                            mapper.writeValueAsBytes(
                                    Map.of(
                                            "access_token",
                                            signed.serialize(),
                                            "token_type",
                                            "Bearer",
                                            "expires_at",
                                            now.plusSeconds(300).toString(),
                                            "expires_in",
                                            300,
                                            "scope",
                                            scope));
                    e.getResponseHeaders().set("Content-Type", "application/json");
                    e.sendResponseHeaders(code, body.length);
                    e.getResponseBody().write(body);
                    e.close();
                });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Clock clock = mock(Clock.class);
            when(clock.instant()).thenReturn(now);
            var environment = new MockEnvironment();
            environment.setActiveProfiles("test");
            var decoder =
                    new JwtConfig()
                            .jwtDecoder(
                                    new AuthProperties(
                                            "http://localhost:8080",
                                            "stockdrop-api",
                                            base + "/jwks"),
                                    clock,
                                    environment);
            var properties =
                    new ClientProperties(
                            "http",
                            "account-service",
                            "ai-service",
                            "product-query-service",
                            "synthetic-secret",
                            subject.toString());
            var provider =
                    new ServiceTokenProviderImpl(
                            // 고정 주소 대신 Account 서비스 ID를 fixture로 바꾸는 LoadBalancer 경로를 탑니다.
                            new InternalHttpImpl(
                                    new FixedLoadBalancer(Map.of("account-service", base))
                                            .restClientBuilder().build(),
                                    properties),
                            properties,
                            mapper,
                            decoder,
                            clock);
            assertThat(provider.token()).isEqualTo(signed.serialize());
            assertThat(provider.token()).isEqualTo(signed.serialize());
            assertThat(count.get()).isEqualTo(1);
            status.set(503);
            when(clock.instant()).thenReturn(now.plusSeconds(280));
            assertThat(provider.token()).isEqualTo(signed.serialize());
            when(clock.instant()).thenReturn(now.plusSeconds(301));
            assertThatThrownBy(provider::token).isInstanceOf(RemoteUnavailable.class);
        } finally {
            server.stop(0);
        }
    }
}
