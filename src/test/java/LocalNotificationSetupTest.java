import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

/** 로컬 설정 도구의 멱등성과 충돌 시 기존 파일 보존을 임시 파일로 확인합니다. */
class LocalNotificationSetupTest {
    @TempDir Path desktop;

    Path prepare() throws Exception {
        Path notification = Files.createDirectory(desktop.resolve("NotificationService"));
        Files.writeString(
                notification.resolve("settings.gradle"), "rootProject.name='NotificationService'");
        Files.writeString(
                notification.resolve(".env"),
                "# preserve\n"
                        + "DB_PASSWORD=synthetic-test-only\n"
                        + "VAPID_PUBLIC_KEY=\n"
                        + "VAPID_PRIVATE_KEY=\n");
        for (String service : new String[] {"AccountService", "AIService"}) {
            Files.createDirectory(desktop.resolve(service));
            Files.writeString(
                    desktop.resolve(service).resolve(".env"), "# keep original\nEXISTING=value\n");
        }
        return notification;
    }

    @Test
    void createsOnceAndPreservesExistingKeysAndComments() throws Exception {
        Path root = prepare();
        LocalNotificationSetup.main(new String[] {root.toString()});
        String first = Files.readString(root.resolve(".env"));
        String account = Files.readString(desktop.resolve("AccountService/.env"));
        LocalNotificationSetup.main(new String[] {root.toString()});
        assertThat(Files.readString(root.resolve(".env"))).isEqualTo(first);
        assertThat(Files.readString(desktop.resolve("AccountService/.env"))).isEqualTo(account);
        assertThat(first).contains("# preserve", "DB_PASSWORD=synthetic-test-only");
        var parsed = LocalNotificationSetup.parse(first);
        assertThat(parsed.get("NOTIFICATION_SERVICE_SUBJECT")).isNotBlank();
        assertThat(parsed.get("VAPID_PUBLIC_KEY")).hasSize(87);
        assertThat(java.util.Base64.getDecoder().decode(parsed.get("PUSH_ENCRYPTION_KEY")))
                .hasSize(32);
    }

    @Test
    void conflictingSubjectsAbortBeforeAnyFileWrite() throws Exception {
        Path root = prepare();
        String before = Files.readString(root.resolve(".env"));
        Files.writeString(
                desktop.resolve("AccountService/.env"),
                "account.service-auth.clients.notification-service.subject=11111111-1111-1111-1111-111111111111\n");
        Files.writeString(
                desktop.resolve("AIService/.env"),
                "NOTIFICATION_SERVICE_SUBJECT=22222222-2222-2222-2222-222222222222\n");
        assertThatThrownBy(() -> LocalNotificationSetup.main(new String[] {root.toString()}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(root.resolve(".env"))).isEqualTo(before);
    }
}
