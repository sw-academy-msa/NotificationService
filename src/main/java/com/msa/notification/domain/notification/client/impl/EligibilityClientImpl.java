package com.msa.notification.domain.notification.client.impl;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.dto.response.Candidate;
import com.msa.notification.global.config.client.ClientProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

/** Account의 최신 동의, AI 참여 상태와 Query의 현재 공개 여부를 각각 API로 확인합니다. */
@Component
public class EligibilityClientImpl implements EligibilityClient {
    private final HttpClient http;
    private final ClientProperties properties;
    private final ServiceTokenProvider tokens;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EligibilityClientImpl(
            HttpClient http,
            ClientProperties properties,
            ServiceTokenProvider tokens,
            ObjectMapper mapper,
            Clock clock) {
        this.http = http;
        this.properties = properties;
        this.tokens = tokens;
        this.mapper = mapper;
        this.clock = clock;
    }

    public String eligibleProductName(Candidate candidate) {
        var account =
                get(
                        properties.account()
                                + "/internal/v1/notification-eligibility?accountId="
                                + candidate.accountId(),
                        true);
        if (!flag(account, "eligible")) return null;
        var ai =
                get(
                        properties.ai()
                                + "/internal/v1/personalization/eligibility?accountId="
                                + candidate.accountId()
                                + "&analyticsId="
                                + candidate.analyticsId(),
                        true);
        if (!flag(ai, "analyticsEnabled")) return null;
        var product =
                get(properties.productQuery() + "/api/v1/products/" + candidate.productId(), false);
        if (!candidate.productId().toString().equals(product.path("id").asText())
                || !product.path("name").isString()
                || product.path("name").asText().isBlank()) throw new RemoteUnavailable();
        String status = product.path("status").asText();
        if (!java.util.Set.of("UPCOMING", "ON_SALE", "SOLD_OUT", "ENDED").contains(status))
            throw new RemoteUnavailable();
        try {
            if ("ENDED".equals(status)
                    || !clock.instant().isBefore(Instant.parse(product.path("endsAt").asText())))
                return null;
        } catch (java.time.format.DateTimeParseException ex) {
            throw new RemoteUnavailable();
        }
        return product.path("name").asText();
    }

    public boolean transactionalAllowed(java.util.UUID account) {
        var data =
                get(
                        properties.account()
                                + "/internal/v1/notification-eligibility?accountId="
                                + account
                                + "&purpose=TRANSACTIONAL",
                        true);
        return flag(data, "eligible");
    }

    private boolean flag(JsonNode value, String field) {
        if (!value.path(field).isBoolean()) throw new RemoteUnavailable();
        return value.path(field).asBoolean();
    }

    private JsonNode get(String address, boolean authenticated) {
        try {
            var builder =
                    HttpRequest.newBuilder(URI.create(address))
                            .timeout(Duration.ofSeconds(5))
                            .header("Accept", "application/json");
            if (authenticated) builder.header("Authorization", "Bearer " + tokens.token());
            var result =
                    http.send(
                            builder.GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (result.statusCode() == 401 && authenticated) tokens.invalidate();
            if (result.statusCode() != 200) throw new RemoteUnavailable();
            var body = mapper.readTree(result.body());
            if (!body.path("success").asBoolean() || !body.path("data").isObject())
                throw new RemoteUnavailable();
            return body.path("data");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RemoteUnavailable();
        } catch (Exception ex) {
            throw new RemoteUnavailable();
        }
    }
}
