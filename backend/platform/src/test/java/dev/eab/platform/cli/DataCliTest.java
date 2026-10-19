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
    @DisplayName("重置清空运行数据，撤回演示发布的租户资产并保留基础设施，重新预置演示委托")
    void resetClearsRuntimeData(@TempDir Path dataDir) {
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            db.jdbc()
                    .sql(
                            "INSERT INTO tenant_settings (tenant_id, l3_enabled, assistant_roles, updated_at) VALUES ('t001', 0, '[]', 'x')")
                    .update();
            var delegations = new DelegationStore(db.jdbc(), CLOCK);
            PlatformServices.ensureDemoDefaults(delegations, CLOCK);
            delegations.revoke(PlatformServices.DEMO_DELEGATION_ID, "zhaoadmin");
            db.jdbc()
                    .sql("INSERT INTO assets (kind, namespace, name, version, layer, tenant_id, status, content_yaml,"
                            + " content_json, content_hash, created_by, created_at, updated_at)"
                            + " VALUES ('DevRequest', 'tenant.t001.requests', 'req-x', '1.0.0', 'tenant', 't001',"
                            + " 'draft', 'x', '{}', 'h', 'zhangmin', '2026-10-15T08:00:00Z', '2026-10-15T08:00:00Z')")
                    .update();
            db.jdbc()
                    .sql("INSERT INTO request_plans (tenant_id, request_ref, plan, adopted, created_at, updated_at)"
                            + " VALUES ('t001', 'tenant.t001.requests/req-x', '{}', '[]', 'x', 'x')")
                    .update();
            // 演示残留：故事 B 经 Studio 发布的扩展；以及启动入库的基础设施（工具来源）
            db.jdbc()
                    .sql(
                            "INSERT INTO assets (kind, namespace, name, version, layer, tenant_id, status, content_yaml,"
                                    + " content_json, content_hash, created_by, created_at, updated_at)"
                                    + " VALUES ('Extension', 'tenant.t001.extensions', 'demo-ext', '0.1.0', 'tenant', 't001',"
                                    + " 'published', 'x', '{}', 'h', 'zhangmin', '2026-10-15T08:00:00Z', '2026-10-15T08:00:00Z')")
                    .update();
            db.jdbc()
                    .sql(
                            "INSERT INTO assets (kind, namespace, name, version, layer, tenant_id, status, content_yaml,"
                                    + " content_json, content_hash, created_by, created_at, updated_at)"
                                    + " VALUES ('ToolSource', 'tenant.t001.tools', 'notify-im', '1.0.0', 'tenant', 't001',"
                                    + " 'published', 'x', '{}', 'h', 'chengong', '2026-10-15T08:00:00Z', '2026-10-15T08:00:00Z')")
                    .update();
        }
        ResetDemo.reset(dataDir, CLOCK);
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            assertThat(db.jdbc()
                            .sql("SELECT COUNT(*) FROM tenant_settings")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(db.jdbc()
                            .sql("SELECT COUNT(*) FROM request_plans")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(db.jdbc()
                            .sql("SELECT COUNT(*) FROM assets WHERE kind = 'DevRequest'")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(db.jdbc()
                            .sql("SELECT COUNT(*) FROM assets WHERE namespace = 'tenant.t001.extensions'")
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(db.jdbc()
                            .sql("SELECT COUNT(*) FROM assets WHERE namespace = 'tenant.t001.tools'")
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
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
