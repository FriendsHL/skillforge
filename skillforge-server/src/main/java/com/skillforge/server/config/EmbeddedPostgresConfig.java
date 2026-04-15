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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

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
        return EmbeddedPostgres.builder()
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(!alreadyInitialized)
                .setPort(15432)
                .setOverrideWorkingDirectory(new File(System.getProperty("user.home"), ".skillforge/pgrun"))
                .start();
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
