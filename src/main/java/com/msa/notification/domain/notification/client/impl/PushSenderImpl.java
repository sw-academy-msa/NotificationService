package com.msa.notification.domain.notification.client.impl;

import com.msa.notification.domain.notification.client.*;
import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.dto.response.NotificationResponses.PushResult;
import com.msa.notification.global.config.push.PushProperties;

import nl.martijndwars.webpush.*;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.*;
import java.time.*;

/** 라이브러리의 RFC8291/VAPID 암호화를 사용하고 실제 전송은 timeout과 redirect 금지 정책으로 제한합니다. */
@Component
public class PushSenderImpl implements PushSender {
    private final PushProperties properties;
    private final HttpClient http;
    private final PreparedPush service;

    public PushSenderImpl(PushProperties properties) {
        this.properties = properties;
        Security.addProvider(new BouncyCastleProvider());
        http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        try {
            service = properties.enabled() ? new PreparedPush(properties) : null;
            if (properties.enabled()) {
                // 유효한 두 키라도 서로 다른 쌍이면 모든 푸시가 실패하므로 시작 시 차단합니다.
                var publicKey =
                        (org.bouncycastle.jce.interfaces.ECPublicKey)
                                Utils.loadPublicKey(properties.publicKey());
                var privateKey =
                        (org.bouncycastle.jce.interfaces.ECPrivateKey)
                                Utils.loadPrivateKey(properties.privateKey());
                if (!publicKey
                        .getQ()
                        .equals(publicKey.getParameters().getG().multiply(privateKey.getD()))) {
                    throw new IllegalArgumentException();
                }
            }
            if (properties.enabled()
                    && (properties.subject() == null
                            || !properties.subject().startsWith("mailto:")
                            || java.util.Base64.getDecoder()
                                            .decode(properties.encryptionKey())
                                            .length
                                    != 32)) throw new IllegalArgumentException();
        } catch (Exception ex) {
            throw new IllegalArgumentException("웹 푸시 키와 연락 주소 설정이 필요합니다.");
        }
    }

    public PushResult send(
            SubscriptionRequest subscription, byte[] payload, int ttl, String topic) {
        if (!properties.enabled() || payload.length > 3000 || ttl < 1)
            throw new RemoteUnavailable();
        try {
            var notification =
                    new Notification(
                            subscription.endpoint(),
                            subscription.keys().p256dh(),
                            subscription.keys().auth(),
                            payload,
                            ttl);
            var prepared = service.prepare(notification);
            var builder =
                    HttpRequest.newBuilder(URI.create(prepared.getUrl()))
                            .timeout(Duration.ofSeconds(10));
            prepared.getHeaders().forEach(builder::header);
            builder.header("Topic", topic);
            var response =
                    http.send(
                            builder.POST(HttpRequest.BodyPublishers.ofByteArray(prepared.getBody()))
                                    .build(),
                            HttpResponse.BodyHandlers.discarding());
            long retry =
                    response.headers().firstValue("Retry-After").map(this::retryAfter).orElse(0L);
            return new PushResult(response.statusCode(), retry);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RemoteUnavailable();
        } catch (Exception ex) {
            throw new RemoteUnavailable();
        }
    }

    private long retryAfter(String value) {
        try {
            return Math.max(0, Math.min(3600, Long.parseLong(value)));
        } catch (Exception ex) {
            try {
                return Math.max(
                        0,
                        Math.min(
                                3600,
                                Duration.between(
                                                Instant.now(),
                                                ZonedDateTime.parse(
                                                                value,
                                                                java.time.format.DateTimeFormatter
                                                                        .RFC_1123_DATE_TIME)
                                                        .toInstant())
                                        .getSeconds()));
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    /** 전송 구현을 상속하지 않고 암호화된 표준 HTTP 요청 생성만 재사용합니다. */
    static final class PreparedPush extends AbstractPushService<PreparedPush> {
        PreparedPush(PushProperties properties) throws Exception {
            super(properties.publicKey(), properties.privateKey(), properties.subject());
        }

        nl.martijndwars.webpush.HttpRequest prepare(Notification notification) throws Exception {
            return prepareRequest(notification, Encoding.AES128GCM);
        }
    }
}
