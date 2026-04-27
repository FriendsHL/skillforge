package com.skillforge.server.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        File dataDir = new File(System.getProperty("user.home"), ".skillforge/pgdata");
        dataDir.mkdirs();
        new File(System.getProperty("user.home"), ".skillforge/pgrun").mkdirs();

        boolean alreadyInitialized = new File(dataDir, "PG_VERSION").exists();
        if (!alreadyInitialized) {
            alreadyInitialized = tryAutoRestoreFromBackup(dataDir.toPath());
        }

        return EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(!alreadyInitialized)
                .setPort(15432)
                .setOverrideWorkingDirectory(new File(System.getProperty("user.home"), ".skillforge/pgrun"))
                .start();
    }

    private boolean tryAutoRestoreFromBackup(Path dataDir) {
        Path backupsDir = Path.of(System.getProperty("user.home"), ".skillforge/backups");
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

    @Bean
    public PostgresBackupService postgresBackupService(EmbeddedPostgres embeddedPostgres) throws IOException {
        return new PostgresBackupService(embeddedPostgres);
    }

    @Bean
    @Primary
    public DataSource dataSource(EmbeddedPostgres embeddedPostgres) throws SQLException {
        // Ensure the 'skillforge' database exists (embedded PG starts with only 'postgres')
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:15432/postgres", "postgres", "");
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
                .url("jdbc:postgresql://localhost:15432/skillforge")
                .username("postgres")
                .password("")
                .driverClassName("org.postgresql.Driver")
                .build();
    }
}
