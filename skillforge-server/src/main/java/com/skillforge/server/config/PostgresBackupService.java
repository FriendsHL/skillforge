package com.skillforge.server.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

public class PostgresBackupService {

    private static final Logger log = LoggerFactory.getLogger(PostgresBackupService.class);
    private static final int RETAIN_COUNT = 5;
    static final String BACKUP_PREFIX = "pgdata-";

    private final EmbeddedPostgres pg;
    private final Path dataDir;
    private final Path backupsDir;

    public PostgresBackupService(EmbeddedPostgres pg) throws IOException {
        this.pg = pg;
        Path home = Path.of(System.getProperty("user.home"));
        this.dataDir = home.resolve(".skillforge/pgdata");
        this.backupsDir = home.resolve(".skillforge/backups");
        Files.createDirectories(this.backupsDir);
        log.info("PostgresBackupService initialized (dataDir={}, backupsDir={}, retain={})",
                dataDir, backupsDir, RETAIN_COUNT);
    }

    @PreDestroy
    public void backupOnShutdown() {
        try {
            Path backup = backup("shutdown");
            log.info("PostgreSQL backup written: {} ({} bytes)", backup, dirSize(backup));
            cleanupOldBackups();
        } catch (Exception e) {
            log.error("PostgreSQL backup at shutdown FAILED — data may be lost on next start", e);
        }
    }

    public Path backup(String reason) throws IOException {
        if (!Files.exists(dataDir.resolve("PG_VERSION"))) {
            throw new IOException("PG data dir not initialized: " + dataDir);
        }
        try (var conn = pg.getPostgresDatabase().getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CHECKPOINT");
        } catch (Exception e) {
            log.warn("CHECKPOINT failed before backup ({}), proceeding with copy anyway", e.getMessage());
        }

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = backupsDir.resolve(BACKUP_PREFIX + ts + "-" + reason);
        copyDir(dataDir, target);
        return target;
    }

    private void cleanupOldBackups() throws IOException {
        try (var stream = Files.list(backupsDir)) {
            List<Path> backups = stream
                    .filter(p -> p.getFileName().toString().startsWith(BACKUP_PREFIX))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (int i = RETAIN_COUNT; i < backups.size(); i++) {
                deleteDir(backups.get(i));
                log.info("Pruned old backup: {}", backups.get(i));
            }
        }
    }

    static void copyDir(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.equals("postmaster.pid") || name.equals("postmaster.opts")) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, dst.resolve(src.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static long dirSize(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        long[] size = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                size[0] += a.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }
}
