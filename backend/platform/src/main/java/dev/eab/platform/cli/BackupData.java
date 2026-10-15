package dev.eab.platform.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * pnpm data:backup
 * 对运行数据做一致性快照：用 SQLite 在线备份复制 platform.db 和 erp.db（服务运行时也安全），
 * 连同签名密钥打包成 data/backups/eab-data-&lt;时间&gt;.tar.gz，只保留最近 10 份。
 * 演示主机是抢占式实例，快照由本地的 scripts/ecs.sh backup 拉回保存；最后一行输出是归档路径。
 */
public final class BackupData {

    private BackupData() {}

    static final int KEEP = 10;
    static final List<String> DATABASES = List.of("platform.db", "erp.db");

    public static Path backup(Path dataDir, Clock clock) throws IOException, InterruptedException {
        Path backupsDir = dataDir.resolve("backups");
        String stamp =
                Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString().replaceAll("[:.]", "-");
        String name = "eab-data-" + stamp;
        Path workDir = backupsDir.resolve(name);
        Files.createDirectories(workDir);

        for (String database : DATABASES) {
            Path source = dataDir.resolve(database);
            if (Files.exists(source)) snapshot(source, workDir.resolve(database));
        }
        Path keys = dataDir.resolve("keys");
        if (Files.isDirectory(keys)) copyTree(keys, workDir.resolve("keys"));

        Path archive = backupsDir.resolve(name + ".tar.gz");
        Process tar = new ProcessBuilder("tar", "-czf", archive.toString(), "-C", backupsDir.toString(), name)
                .inheritIO()
                .start();
        if (tar.waitFor() != 0) throw new IOException("tar 打包失败，退出码 " + tar.exitValue());
        deleteTree(workDir);
        prune(backupsDir);
        return archive;
    }

    /** SQLite 在线备份：服务正在写入时也能得到一致的快照 */
    static void snapshot(Path source, Path target) throws IOException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source);
                var statement = connection.createStatement()) {
            statement.executeUpdate("backup to '" + target.toString().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new IOException("备份 " + source.getFileName() + " 失败：" + e.getMessage(), e);
        }
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** 只保留最近 KEEP 份（文件名里的时间戳按字符串排序即按时间排序） */
    static void prune(Path backupsDir) throws IOException {
        try (Stream<Path> files = Files.list(backupsDir)) {
            List<Path> archives = files.filter(p -> {
                        String file = p.getFileName().toString();
                        return file.startsWith("eab-data-") && file.endsWith(".tar.gz");
                    })
                    .sorted()
                    .toList();
            for (Path old : archives.subList(0, Math.max(0, archives.size() - KEEP))) Files.delete(old);
        }
    }

    public static void main(String[] args) {
        Ansi.quietLogs();
        try {
            System.out.println(backup(DataDirs.resolve(), Clock.systemUTC()));
        } catch (IOException e) {
            System.err.println("备份失败：" + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        } catch (UncheckedIOException e) {
            System.err.println("备份失败：" + e.getMessage());
            System.exit(1);
        }
    }
}
