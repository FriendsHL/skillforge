package com.skillforge.server.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.Optional;

@Configuration
@ConditionalOnProperty(name = "skillforge.embedded-postgres.enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    /**
     * Listener port (default 15432 = dev). The desktop app overrides this to 15433 so
     * it never contends with a running dev server.
     */
    private final int port;

    /**
     * Anchor dir for PG state: {@code <baseDir>/pgdata}, {@code <baseDir>/pgrun},
     * {@code <baseDir>/backups}. Default {@code ~/.skillforge} (dev byte-for-byte
     * unchanged). The desktop app overrides this to {@code ~/.skillforge-desktop}.
     */
    private final Path baseDir;

    /**
     * One-time seed source (a PG data dir). When set AND the target data dir is empty
     * (no PG_VERSION) AND no backup was restored, the source is copied in once so a
     * fresh desktop DB starts pre-populated from dev data. Default empty = no seed.
     */
    private final String seedFrom;

    public EmbeddedPostgresConfig(
            @Value("${skillforge.embedded-postgres.port:15432}") int port,
            @Value("${skillforge.embedded-postgres.base-dir:}") String baseDir,
            @Value("${skillforge.embedded-postgres.seed-from:}") String seedFrom) {
        this.port = port;
        this.baseDir = (baseDir == null || baseDir.isBlank())
                ? Path.of(System.getProperty("user.home"), ".skillforge")
                : Path.of(baseDir);
        this.seedFrom = seedFrom;
    }

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        File dataDir = baseDir.resolve("pgdata").toFile();
        dataDir.mkdirs();
        File pgRun = baseDir.resolve("pgrun").toFile();
        pgRun.mkdirs();

        // Seed priority: already-initialized > backup restore > seed-from > fresh empty.
        boolean alreadyInitialized = new File(dataDir, "PG_VERSION").exists();
        if (!alreadyInitialized) {
            alreadyInitialized = tryAutoRestoreFromBackup(dataDir.toPath());
        }
        if (!alreadyInitialized) {
            alreadyInitialized = trySeedFrom(dataDir.toPath());
        }

        log.info("Embedded PG starting: port={} dataDir={} pgRun={} initialized={}",
                port, dataDir, pgRun, alreadyInitialized);

        return EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(!alreadyInitialized)
                .setPort(port)
                .setOverrideWorkingDirectory(pgRun)
                .start();
    }

    private boolean tryAutoRestoreFromBackup(Path dataDir) {
        Path backupsDir = baseDir.resolve("backups");
        if (!Files.exists(backupsDir)) return false;
        try (var stream = Files.list(backupsDir)) {
            Optional<Path> latest = stream
                    .filter(p -> p.getFileName().toString().startsWith(PostgresBackupService.BACKUP_PREFIX))
                    .filter(p -> Files.exists(p.resolve("PG_VERSION")))
                    .max(Comparator.naturalOrder());
            if (latest.isEmpty()) return false;
            log.warn("PG data dir is empty — auto-restoring from latest backup: {}", latest.get());
            PostgresBackupService.deleteDir(dataDir);
            PostgresBackupService.copyDir(latest.get(), dataDir);
            log.info("Auto-restore complete from {}, PG will start with restored data", latest.get());
            return true;
        } catch (IOException e) {
            log.error("Auto-restore failed — PG will start with a fresh empty data dir", e);
            return false;
        }
    }

    /**
     * One-time copy of {@code seedFrom} (e.g. the dev data dir) into an empty target.
     * Crash-consistent: the source may be live (dev server running); copyDir skips
     * runtime files (postmaster.pid/.opts/epg-lock) so the seeded dir is loadable and
     * the first start runs WAL crash recovery. Best-effort — any failure falls back to
     * a fresh empty DB.
     */
    boolean trySeedFrom(Path dataDir) {
        if (seedFrom == null || seedFrom.isBlank()) return false;
        Path src = Path.of(seedFrom);
        if (!Files.exists(src.resolve("PG_VERSION"))) {
            log.warn("seed-from set but no PG_VERSION at {} — starting fresh empty DB", src);
            return false;
        }
        try {
            log.info("Seeding fresh PG data dir from {} (one-time copy)", src);
            PostgresBackupService.deleteDir(dataDir);
            PostgresBackupService.copyDir(src, dataDir);
            log.info("Seed complete from {}; PG will start with seeded data (crash recovery if needed)", src);
            return true;
        } catch (IOException e) {
            log.error("Seed from {} failed — starting fresh empty DB", src, e);
            return false;
        }
    }

    @Bean
    public PostgresBackupService postgresBackupService(EmbeddedPostgres embeddedPostgres) throws IOException {
        return new PostgresBackupService(embeddedPostgres, baseDir);
    }

    @Bean
    @Primary
    public DataSource dataSource(EmbeddedPostgres embeddedPostgres) throws SQLException {
        // Ensure the 'skillforge' database exists (embedded PG starts with only 'postgres')
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:" + port + "/postgres", "postgres", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE skillforge");
            log.info("Created database 'skillforge'.");
        } catch (SQLException e) {
            if ("42P04".equals(e.getSQLState())) {
                log.debug("Database 'skillforge' already exists.");
            } else {
                throw e;
            }
        }
        return DataSourceBuilder.create()
                .url("jdbc:postgresql://localhost:" + port + "/skillforge")
                .username("postgres")
                .password("")
                .driverClassName("org.postgresql.Driver")
                .build();
    }

    // ── Visible for tests (no PG start) ──
    Path baseDir() {
        return baseDir;
    }

    int port() {
        return port;
    }
}
