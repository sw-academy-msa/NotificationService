package com.msa.notification.global.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.msa.notification.global.config.database.NotificationDatabaseSupport;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

import java.net.*;
import java.time.*;
import java.util.*;

/** 서명 검증과 roles 변환을 실제 Bearer 요청으로 검증합니다. */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
class NotificationSecurityIntegrationTest extends NotificationDatabaseSupport {
    static final RSAKey KEY;
    static final HttpServer JWKS;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("synthetic-notification-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext(
                    "/jwks",
                    e -> {
                        byte[] bytes =
                                new JWKSet(KEY.toPublicJWK())
                                        .toString()
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        e.getResponseHeaders().set("Content-Type", "application/json");
                        e.sendResponseHeaders(200, bytes.length);
                        e.getResponseBody().write(bytes);
                        e.close();
                    });
            JWKS.start();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "notification.auth.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }

    @AfterAll
    static void stop() {
        JWKS.stop(0);
    }

    @Autowired MockMvc mvc;

    String token(String role, String audience) throws Exception {
        var claims =
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:8080")
                        .audience(audience)
                        .subject(UUID.randomUUID().toString())
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .claim("token_use", "access")
                        .claim("roles", role == null ? List.of() : List.of(role))
                        .build();
        var signed =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                        claims);
        signed.sign(new RSASSASigner(KEY));
        return signed.serialize();
    }

    @Test
    void roleClaimReachesInboxAndMissingRoleIsForbidden() throws Exception {
        mvc.perform(
                        get("/api/v1/notifications")
                                .header(
                                        "Authorization",
                                        "Bearer " + token("BUYER", "stockdrop-api")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(
                        get("/api/v1/notifications")
                                .header("Authorization", "Bearer " + token(null, "stockdrop-api")))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidAudienceIsRejectedByConfiguredDecoder() throws Exception {
        mvc.perform(
                        get("/api/v1/notifications")
                                .header("Authorization", "Bearer " + token("BUYER", "other-api")))
                .andExpect(status().isUnauthorized());
    }
}
