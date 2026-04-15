package com.skillforge.server.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.util.List;
import java.util.Map;

/**
 * One-time migration runner: copies all rows from H2 file to PostgreSQL.
 * Idempotent: skips if ~/.skillforge/h2_migration_done flag file exists.
 */
@Component
public class H2DataMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(H2DataMigrationRunner.class);

    // Tables ordered to respect any implicit dependencies (reference tables first)
    private static final String[] TABLES = {
        "t_agent", "t_skill", "t_user_config",
        "t_session", "t_memory", "t_model_usage",
        "t_activity_log", "t_trace_span", "t_compaction_event",
        "t_subagent_run", "t_subagent_pending_result", "t_collab_run"
    };

    // Tables with BIGSERIAL PKs — sequences need to be bumped after data load
    private static final String[][] SEQUENCE_TABLES = {
        {"t_agent",                    "t_agent_id_seq"},
        {"t_skill",                    "t_skill_id_seq"},
        {"t_user_config",              "t_user_config_id_seq"},
        {"t_memory",                   "t_memory_id_seq"},
        {"t_model_usage",              "t_model_usage_id_seq"},
        {"t_activity_log",             "t_activity_log_id_seq"},
        {"t_compaction_event",         "t_compaction_event_id_seq"},
        {"t_subagent_pending_result",  "t_subagent_pending_result_id_seq"},
    };

    private final DataSource pgDataSource;

    @Value("${skillforge.h2-migration.source-path:#{systemProperties['user.dir']}/data/skillforge}")
    private String h2SourcePath;

    public H2DataMigrationRunner(DataSource pgDataSource) {
        this.pgDataSource = pgDataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path flagFile = Path.of(System.getProperty("user.home"), ".skillforge", "h2_migration_done");
        if (Files.exists(flagFile)) {
            log.info("H2 migration already completed, skipping.");
            return;
        }

        File h2File = new File(h2SourcePath + ".mv.db");
        if (!h2File.exists()) {
            log.info("No H2 source file found at {}, skipping migration.", h2File.getAbsolutePath());
            Files.createDirectories(flagFile.getParent());
            Files.createFile(flagFile);
            return;
        }

        log.info("Starting H2 → PostgreSQL data migration from: {}", h2File.getAbsolutePath());

        DriverManagerDataSource h2Ds = new DriverManagerDataSource();
        h2Ds.setDriverClassName("org.h2.Driver");
        h2Ds.setUrl("jdbc:h2:file:" + h2SourcePath + ";AUTO_SERVER=FALSE;ACCESS_MODE_DATA=r");
        h2Ds.setUsername("sa");
        h2Ds.setPassword("");

        JdbcTemplate h2 = new JdbcTemplate(h2Ds);
        JdbcTemplate pg = new JdbcTemplate(pgDataSource);

        for (String table : TABLES) {
            try {
                migrateTable(h2, pg, table);
            } catch (Exception e) {
                log.warn("Table {} migration failed (may not exist in H2): {}", table, e.getMessage());
            }
        }

        // Bump BIGSERIAL sequences to avoid PK collisions after migration
        resetSequences(pg);

        Files.createDirectories(flagFile.getParent());
        Files.createFile(flagFile);
        log.info("H2 → PostgreSQL migration completed. Flag written to {}", flagFile);
    }

    private void migrateTable(JdbcTemplate h2, JdbcTemplate pg, String table) {
        List<Map<String, Object>> rows = h2.queryForList("SELECT * FROM " + table);
        if (rows.isEmpty()) {
            log.info("Table {} is empty, skipping.", table);
            return;
        }
        log.info("Migrating {} rows from table {}...", rows.size(), table);

        for (Map<String, Object> row : rows) {
            String cols = String.join(", ", row.keySet().stream()
                    .map(k -> "\"" + k.toLowerCase() + "\"").toList());
            String placeholders = String.join(", ", row.keySet().stream().map(k -> "?").toList());
            String sql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders
                    + ") ON CONFLICT DO NOTHING";

            Object[] values = row.values().stream()
                    .map(this::convertValue)
                    .toArray();
            pg.update(sql, values);
        }
        log.info("Table {} migrated successfully.", table);
    }

    /** Convert H2-specific types (e.g. Clob) to plain Java types for PostgreSQL. */
    private Object convertValue(Object val) {
        if (val instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (Exception e) {
                log.warn("Failed to read CLOB value, substituting empty string: {}", e.getMessage());
                return "";
            }
        }
        return val;
    }

    /** After bulk insert, bump each BIGSERIAL sequence to MAX(id) to avoid PK conflicts. */
    private void resetSequences(JdbcTemplate pg) {
        for (String[] entry : SEQUENCE_TABLES) {
            String table = entry[0];
            String seq   = entry[1];
            try {
                pg.execute(String.format(
                    "SELECT setval('%s', GREATEST(1000, COALESCE((SELECT MAX(id) FROM %s), 0) + 1))",
                    seq, table));
                log.debug("Reset sequence {} for table {}", seq, table);
            } catch (Exception e) {
                log.warn("Failed to reset sequence {} : {}", seq, e.getMessage());
            }
        }
    }
}
