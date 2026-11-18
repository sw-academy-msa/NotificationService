import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

/** 명시 실행 시에만 로컬 .env에 키를 저장합니다. 실제 값은 콘솔에 출력하지 않습니다. */
public class LocalNotificationSetup {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path account = root.resolveSibling("AccountService").resolve(".env"),
                ai = root.resolveSibling("AIService").resolve(".env"),
                own = root.resolve(".env");
        if (!Files.readString(root.resolve("settings.gradle")).contains("NotificationService"))
            throw new IllegalStateException("프로젝트를 확인해 주세요.");
        for (Path path : List.of(own, account, ai))
            if (!Files.isRegularFile(path))
                throw new IllegalStateException("각 서비스 .env와 Notification DB를 먼저 준비해 주세요.");
        Map<Path, String> before = new LinkedHashMap<>();
        for (Path path : List.of(own, account, ai)) before.put(path, Files.readString(path));
        Map<Path, LinkedHashMap<String, String>> envs = new LinkedHashMap<>();
        before.forEach((path, text) -> envs.put(path, parse(text)));
        var mine = envs.get(own);
        var auth = envs.get(account);
        var analysis = envs.get(ai);
        String prefix = "account.service-auth.clients.notification-service.";
        String subject =
                existing(
                        mine.get("NOTIFICATION_SERVICE_SUBJECT"),
                        auth.get(prefix + "subject"),
                        analysis.get("NOTIFICATION_SERVICE_SUBJECT"));
        if (subject == null) subject = UUID.randomUUID().toString();
        UUID.fromString(subject);
        String secret = mine.get("NOTIFICATION_CLIENT_SECRET");
        if (secret == null || secret.isBlank()) secret = random();
        var encoder = new BCryptPasswordEncoder(12);
        String hash = auth.get(prefix + "secret-hash");
        if (hash != null && !hash.isBlank() && !encoder.matches(secret, hash))
            throw new IllegalStateException("기존 서비스 인증 정보와 충돌합니다. 자동 교체하지 않습니다.");
        mine.put("NOTIFICATION_SERVICE_SUBJECT", subject);
        mine.put("NOTIFICATION_CLIENT_SECRET", secret);
        auth.put("account.service-auth.enabled", "true");
        auth.put(prefix + "subject", subject);
        auth.put(
                prefix + "secret-hash",
                hash == null || hash.isBlank() ? encoder.encode(secret) : hash);
        auth.put(prefix + "scopes", "account.notifications.read,ai.personalization.read");
        analysis.put("NOTIFICATION_SERVICE_SUBJECT", subject);
        String publicKey = mine.get("VAPID_PUBLIC_KEY"), privateKey = mine.get("VAPID_PRIVATE_KEY");
        if (blank(publicKey) != blank(privateKey))
            throw new IllegalStateException("VAPID 키 쌍은 함께 관리해야 합니다.");
        if (blank(publicKey)) {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            byte[] pub = new byte[65];
            pub[0] = 4;
            var point = ((ECPublicKey) pair.getPublic()).getW();
            coordinate(point.getAffineX(), pub, 1);
            coordinate(point.getAffineY(), pub, 33);
            byte[] priv = new byte[32];
            coordinate(((ECPrivateKey) pair.getPrivate()).getS(), priv, 0);
            var b64 = Base64.getUrlEncoder().withoutPadding();
            mine.put("VAPID_PUBLIC_KEY", b64.encodeToString(pub));
            mine.put("VAPID_PRIVATE_KEY", b64.encodeToString(priv));
        }
        if (blank(mine.get("PUSH_ENCRYPTION_KEY"))) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            mine.put("PUSH_ENCRYPTION_KEY", Base64.getEncoder().encodeToString(key));
        }
        // 전체 파일을 먼저 확인합니다. 개별 교체는 원자적이지만 세 파일은 하나의 파일시스템 트랜잭션이 아닙니다.
        for (var entry : before.entrySet())
            if (!Files.readString(entry.getKey()).equals(entry.getValue()))
                throw new IllegalStateException("설정이 작업 중 변경됐습니다.");
        for (var entry : envs.entrySet()) {
            String text = before.get(entry.getKey());
            var old = parse(text);
            for (var value : entry.getValue().entrySet()) {
                if (Objects.equals(old.get(value.getKey()), value.getValue())) continue;
                String line = value.getKey() + "=" + value.getValue();
                if (old.containsKey(value.getKey()))
                    text =
                            text.replaceAll(
                                    "(?m)^"
                                            + java.util.regex.Pattern.quote(value.getKey())
                                            + "=[^\r\n]*",
                                    java.util.regex.Matcher.quoteReplacement(line));
                else text += (text.endsWith("\n") ? "" : "\n") + line + "\n";
            }
            Path temp =
                    Files.createTempFile(entry.getKey().getParent(), "notification-setup-", ".tmp");
            try {
                Files.writeString(temp, text, StandardCharsets.UTF_8);
                Files.move(
                        temp,
                        entry.getKey(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        System.out.println("로컬 인증과 푸시 키를 파일에 저장했습니다. 연락 주소와 기능 활성화 설정을 확인한 뒤 서비스들을 재시작해 주세요.");
    }

    static LinkedHashMap<String, String> parse(String text) {
        var map = new LinkedHashMap<String, String>();
        for (String line : text.replace("\uFEFF", "").split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int i = line.indexOf('=');
            if (i < 1) throw new IllegalStateException(".env 형식을 확인해 주세요.");
            String key = line.substring(0, i);
            if (map.putIfAbsent(key, line.substring(i + 1)) != null)
                throw new IllegalStateException("중복 설정을 먼저 정리해 주세요.");
        }
        return map;
    }

    static String existing(String... values) {
        String result = null;
        for (String value : values)
            if (!blank(value)) {
                if (result != null && !result.equals(value))
                    throw new IllegalStateException("서비스 주체가 서로 다릅니다.");
                result = value;
            }
        return result;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static void coordinate(java.math.BigInteger value, byte[] target, int offset) {
        byte[] data = value.toByteArray();
        System.arraycopy(
                data,
                Math.max(0, data.length - 32),
                target,
                offset + Math.max(0, 32 - data.length),
                Math.min(32, data.length));
    }
}
