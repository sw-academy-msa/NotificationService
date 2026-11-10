package com.msa.notification.domain.notification.client.impl;

import static org.assertj.core.api.Assertions.*;

import com.msa.notification.global.config.push.PushProperties;

import nl.martijndwars.webpush.Notification;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import java.security.*;
import java.security.interfaces.*;
import java.util.*;

/** 실제 Apple 호출 없이 라이브러리의 암호화와 VAPID 헤더를 생성합니다. */
class PushSenderImplTest {
    @Test
    void preparesEncryptedAes128GcmWithVapid() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var vapid = generator.generateKeyPair();
        var user = generator.generateKeyPair();
        byte[] publicKey =
                ((org.bouncycastle.jce.interfaces.ECPublicKey) vapid.getPublic())
                        .getQ()
                        .getEncoded(false);
        byte[] d = ((ECPrivateKey) vapid.getPrivate()).getS().toByteArray();
        byte[] secret = new byte[32];
        System.arraycopy(
                d,
                Math.max(0, d.length - 32),
                secret,
                Math.max(0, 32 - d.length),
                Math.min(32, d.length));
        var encoding = Base64.getUrlEncoder().withoutPadding();
        var properties =
                new PushProperties(
                        true,
                        encoding.encodeToString(publicKey),
                        encoding.encodeToString(secret),
                        "mailto:operator@example.com",
                        Base64.getEncoder().encodeToString(new byte[32]));
        var service = new PushSenderImpl.PreparedPush(properties);
        var request =
                service.prepare(
                        new Notification(
                                "https://web.push.apple.com/test",
                                (org.bouncycastle.jce.interfaces.ECPublicKey) user.getPublic(),
                                new byte[16],
                                "visible notification".getBytes(),
                                60,
                                null,
                                null));
        assertThat(request.getHeaders().get("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(request.getHeaders().get("Authorization")).startsWith("vapid ");
        assertThat(request.getBody()).isNotEmpty();
        assertThat(new String(request.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("visible notification");
    }
}
