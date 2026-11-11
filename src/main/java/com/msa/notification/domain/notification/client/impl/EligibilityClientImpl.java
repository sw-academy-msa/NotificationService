package com.msa.notification.domain.notification.client.impl;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.dto.response.Candidate;
import com.msa.notification.global.config.client.ClientProperties;

import org.springframework.stereotype.Component;

import tools.jackson.databind.*;

import java.time.*;
import java.util.*;

/** Account의 최신 동의, AI 참여 상태와 Query의 현재 공개 여부를 각각 API로 확인합니다. */
@Component
public class EligibilityClientImpl implements EligibilityClient {
    private final InternalHttp http;
    private final ClientProperties properties;
    private final ServiceTokenProvider tokens;
    private final ObjectMapper mapper;
    private final Clock clock;

    public EligibilityClientImpl(
            InternalHttp http,
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
                        properties.account(),
                        "/internal/v1/notification-eligibility?accountId=" + candidate.accountId(),
                        true);
        if (!flag(account, "eligible")) return null;
        var ai =
                get(
                        properties.ai(),
                        "/internal/v1/personalization/eligibility?accountId="
                                + candidate.accountId()
                                + "&analyticsId="
                                + candidate.analyticsId(),
                        true);
        if (!flag(ai, "analyticsEnabled")) return null;
        var product =
                get(
                        properties.productQuery(),
                        "/api/v1/products/" + candidate.productId(),
                        false);
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
                        properties.account(),
                        "/internal/v1/notification-eligibility?accountId="
                                + account
                                + "&purpose=TRANSACTIONAL",
                        true);
        return flag(data, "eligible");
    }

    private boolean flag(JsonNode value, String field) {
        if (!value.path(field).isBoolean()) throw new RemoteUnavailable();
        return value.path(field).asBoolean();
    }

    private JsonNode get(String service, String pathAndQuery, boolean authenticated) {
        try {
            // 서비스 ID로 보내 LoadBalancer가 인스턴스를 고릅니다. 선택·전송 실패도 동의 거절이 아닌 재시도 대상으로 다룹니다.
            var headers = new LinkedHashMap<String, String>();
            headers.put("Accept", "application/json");
            if (authenticated) headers.put("Authorization", "Bearer " + tokens.token());
            var result = http.send(service, "GET", pathAndQuery, headers, null);
            if (result.status() == 401 && authenticated) tokens.invalidate();
            if (result.status() != 200) throw new RemoteUnavailable();
            var body = mapper.readTree(result.body());
            if (!body.path("success").asBoolean() || !body.path("data").isObject())
                throw new RemoteUnavailable();
            return body.path("data");
        } catch (Exception ex) {
            throw new RemoteUnavailable();
        }
    }
}
