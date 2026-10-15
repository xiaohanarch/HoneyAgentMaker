package dev.eab.platform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.db.PlatformDb;
import dev.eab.platform.identity.DelegationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** pnpm seed:reset 与 pnpm data:backup 的平台部分 */
class DataCliTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("重置清空审计、事件、委托、租户设置，并重新预置演示委托")
    void resetClearsRuntimeData(@TempDir Path dataDir) {
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            db.jdbc()
                    .sql(
                            "INSERT INTO tenant_settings (tenant_id, l3_enabled, assistant_roles, updated_at) VALUES ('t001', 0, '[]', 'x')")
                    .update();
            var delegations = new DelegationStore(db.jdbc(), CLOCK);
            PlatformServices.ensureDemoDefaults(delegations, CLOCK);
            delegations.revoke(PlatformServices.DEMO_DELEGATION_ID, "zhaoadmin");
        }
        ResetDemo.reset(dataDir, CLOCK);
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            assertThat(db.jdbc()
                            .sql("SELECT COUNT(*) FROM tenant_settings")
                            .query(Long.class)
                            .single())
                    .isZero();
            var status = new DelegationStore(db.jdbc(), CLOCK).status(PlatformServices.DEMO_DELEGATION_ID);
            assertThat(status.active()).isTrue();
        }
    }

    @Test
    @DisplayName("备份打包数据库和密钥，只保留最近 10 份")
    void backupArchivesAndPrunes(@TempDir Path dataDir) throws Exception {
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            PlatformServices.ensureDemoDefaults(new DelegationStore(db.jdbc(), CLOCK), CLOCK);
        }
        Files.createDirectories(dataDir.resolve("keys"));
        Files.writeString(dataDir.resolve("keys/identity-ed25519.pem"), "test");
        Path backups = Files.createDirectories(dataDir.resolve("backups"));
        for (int i = 0; i < 12; i++)
            Files.writeString(backups.resolve("eab-data-2000-01-%02d.tar.gz".formatted(i)), "");

        Path archive = BackupData.backup(dataDir, CLOCK);

        assertThat(archive).exists();
        assertThat(archive.getFileName().toString()).isEqualTo("eab-data-2026-10-15T08-00-00Z.tar.gz");
        try (Stream<Path> files = Files.list(backups)) {
            var names = files.map(p -> p.getFileName().toString()).toList();
            assertThat(names)
                    .hasSize(BackupData.KEEP)
                    .contains(archive.getFileName().toString());
            assertThat(names).doesNotContain("eab-data-2000-01-00.tar.gz", "eab-data-2026-10-15T08-00-00Z");
        }
        Process list = new ProcessBuilder("tar", "-tzf", archive.toString()).start();
        String entries = new String(list.getInputStream().readAllBytes());
        assertThat(list.waitFor()).isZero();
        assertThat(entries).contains("platform.db").contains("keys/identity-ed25519.pem");
    }
}
