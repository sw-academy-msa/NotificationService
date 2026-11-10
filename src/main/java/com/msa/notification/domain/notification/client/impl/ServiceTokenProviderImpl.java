package com.msa.notification.domain.notification.client.impl;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.global.config.client.ClientProperties;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 기존 Order와 같은 Account 발급 계약을 재사용합니다. 네트워크 실패 시 유효한 캐시만 유지합니다. */
@Component
public class ServiceTokenProviderImpl implements ServiceTokenProvider {
    private static final Set<String> SCOPES =
            Set.of("account.notifications.read", "ai.personalization.read");
    private final HttpClient http;
    private final ClientProperties properties;
    private final ObjectMapper mapper;
    private final JwtDecoder decoder;
    private final Clock clock;
    private String cached;
    private Instant expiresAt = Instant.MIN;
    private Instant retryAt = Instant.MIN;

    public ServiceTokenProviderImpl(
            HttpClient http,
            ClientProperties properties,
            ObjectMapper mapper,
            JwtDecoder decoder,
            Clock clock) {
        this.http = http;
        this.properties = properties;
        this.mapper = mapper;
        this.decoder = decoder;
        this.clock = clock;
    }

    public synchronized String token() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expiresAt.minusSeconds(30))) return cached;
        if (now.isBefore(retryAt)) return valid();
        try {
            if (properties.clientSecret() == null
                    || properties.clientSecret().isBlank()
                    || properties.serviceSubject() == null
                    || properties.serviceSubject().isBlank()) throw new RemoteUnavailable();
            String basic =
                    Base64.getEncoder()
                            .encodeToString(
                                    ("notification-service:" + properties.clientSecret())
                                            .getBytes(StandardCharsets.UTF_8));
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            properties.account()
                                                    + "/internal/v1/auth/service-tokens"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Basic " + basic)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "grant_type=client_credentials&scope=account.notifications.read%20ai.personalization.read"))
                            .build();
            var response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new RemoteUnavailable();
            var data = mapper.readTree(response.body());
            String token = data.path("access_token").asText();
            var jwt = decoder.decode(token);
            var scopes = new HashSet<>(Arrays.asList(jwt.getClaimAsString("scope").split(" +")));
            var roles = jwt.getClaimAsStringList("roles");
            if (!"Bearer".equals(data.path("token_type").asText())
                    || !properties.serviceSubject().equals(jwt.getSubject())
                    || !"notification-service".equals(jwt.getClaimAsString("client_id"))
                    || !"service".equals(jwt.getClaimAsString("principal_type"))
                    || !SCOPES.equals(scopes)
                    || roles == null
                    || !roles.isEmpty()
                    || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(clock.instant())
                    || !jwt.getExpiresAt()
                            .equals(
                                    Instant.parse(data.path("expires_at").asText())
                                            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                    || data.path("expires_in").asLong() <= 0
                    || data.path("expires_in").asLong() > 900
                    || !new HashSet<>(Arrays.asList(data.path("scope").asText().split(" +")))
                            .equals(SCOPES)) throw new RemoteUnavailable();
            cached = token;
            expiresAt = jwt.getExpiresAt();
            retryAt = Instant.MIN;
            return cached;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            retryAt = clock.instant().plusSeconds(5);
            return valid();
        } catch (Exception ex) {
            retryAt = clock.instant().plusSeconds(5);
            return valid();
        }
    }

    private String valid() {
        if (cached != null && clock.instant().isBefore(expiresAt)) return cached;
        throw new RemoteUnavailable();
    }

    public synchronized void invalidate() {
        cached = null;
        expiresAt = Instant.MIN;
        retryAt = Instant.MIN;
    }
}
