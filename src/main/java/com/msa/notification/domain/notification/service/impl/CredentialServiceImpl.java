package com.msa.notification.domain.notification.service.impl;

import com.msa.notification.domain.notification.dto.request.SubscriptionRequest;
import com.msa.notification.domain.notification.exception.*;
import com.msa.notification.domain.notification.service.CredentialService;
import com.msa.notification.global.config.push.PushProperties;

import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** iPhone Push 서비스만 허용해 SSRF를 막고, 계정과 구독 ID를 GCM AAD에 결합합니다. */
@Service
public class CredentialServiceImpl implements CredentialService {
    private final PushProperties properties;
    private final ObjectMapper mapper;

    public CredentialServiceImpl(PushProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public String validateAndHash(SubscriptionRequest request) {
        try {
            URI uri = URI.create(request.endpoint());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"web.push.apple.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw invalid();
            byte[] publicKey = Base64.getUrlDecoder().decode(request.keys().p256dh());
            byte[] auth = Base64.getUrlDecoder().decode(request.keys().auth());
            if (publicKey.length != 65 || publicKey[0] != 4 || auth.length != 16) throw invalid();
            var params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            var spec = params.getParameterSpec(ECParameterSpec.class);
            var x = new java.math.BigInteger(1, Arrays.copyOfRange(publicKey, 1, 33));
            var y = new java.math.BigInteger(1, Arrays.copyOfRange(publicKey, 33, 65));
            var prime = ((ECFieldFp) spec.getCurve().getField()).getP();
            if (x.compareTo(prime) >= 0
                    || y.compareTo(prime) >= 0
                    || !y.multiply(y)
                            .mod(prime)
                            .equals(
                                    x.pow(3)
                                            .add(spec.getCurve().getA().multiply(x))
                                            .add(spec.getCurve().getB())
                                            .mod(prime))) throw invalid();
            String canonical =
                    "https://web.push.apple.com"
                            + uri.getRawPath()
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NotificationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw invalid();
        }
    }

    public String encrypt(SubscriptionRequest request, String aad) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, aad);
            byte[] data = cipher.doFinal(mapper.writeValueAsBytes(request));
            byte[] combined = new byte[iv.length + data.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(data, 0, combined, iv.length, data.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public SubscriptionRequest decrypt(String value, String aad) {
        try {
            byte[] data = Base64.getDecoder().decode(value);
            if (data.length < 29) throw unavailable();
            return mapper.readValue(
                    cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(data, 12), aad)
                            .doFinal(Arrays.copyOfRange(data, 12, data.length)),
                    SubscriptionRequest.class);
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    private Cipher cipher(int mode, byte[] iv, String aad) throws Exception {
        byte[] key = Base64.getDecoder().decode(properties.encryptionKey());
        if (key.length != 32) throw unavailable();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private NotificationException invalid() {
        return new NotificationException(NotificationErrorCode.INVALID_REQUEST);
    }

    private NotificationException unavailable() {
        return new NotificationException(NotificationErrorCode.UNAVAILABLE);
    }
}
