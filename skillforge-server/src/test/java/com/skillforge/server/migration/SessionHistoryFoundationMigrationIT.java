package com.skillforge.server.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionHistoryFoundationMigrationIT {

    private static final String MIGRATOR_ROLE = "sf_history_migrator";
    private static final String RUNTIME_ROLE = "skillforge_app";
    private static final String POPULATED_DATABASE = "history_populated";
    private static final String CLEAN_DATABASE = "history_clean";
    private static EmbeddedPostgres postgres;

    private static DataSource migratorDataSource;
    private static DataSource runtimeDataSource;
    private static JdbcTemplate migratorJdbc;
    private static JdbcTemplate runtimeJdbc;

    @BeforeAll
    static void migrateFromPopulatedV195() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        JdbcTemplate admin = new JdbcTemplate(postgres.getPostgresDatabase());
        admin.execute("CREATE ROLE " + MIGRATOR_ROLE + " LOGIN");
        admin.execute("CREATE ROLE " + RUNTIME_ROLE + " LOGIN");
        admin.execute("CREATE DATABASE " + POPULATED_DATABASE + " OWNER " + MIGRATOR_ROLE);
        admin.execute("CREATE DATABASE " + CLEAN_DATABASE + " OWNER " + MIGRATOR_ROLE);

        migratorDataSource = roleDataSource(POPULATED_DATABASE, MIGRATOR_ROLE);
        runtimeDataSource = roleDataSource(POPULATED_DATABASE, RUNTIME_ROLE);
        migratorJdbc = new JdbcTemplate(migratorDataSource);
        runtimeJdbc = new JdbcTemplate(runtimeDataSource);

        migrateTo(migratorDataSource, "195");
        insertV195Fixture(migratorJdbc);
        migrateTo(migratorDataSource, "198");
    }

    @AfterAll
    static void closeDataSources() throws SQLException, IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    @Order(1)
    void migrate_cleanDatabase_reachesV198() {
        DataSource clean = roleDataSource(CLEAN_DATABASE, MIGRATOR_ROLE);

        migrateTo(clean, "198");

        JdbcTemplate jdbc = new JdbcTemplate(clean);
        assertThat(jdbc.queryForObject(
                """
                SELECT version FROM flyway_schema_history
                WHERE success AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """,
                String.class)).isEqualTo("198");
        assertThat(columnExists(jdbc, "t_session_tool_attempt", "post_action_state")).isTrue();
        assertThat(columnExists(jdbc, "t_session_tool_attempt",
                "post_action_inbox_handoff_accepted")).isTrue();
        assertThat(columnExists(jdbc, "t_session_compaction_checkpoint",
                "sidecar_watermark")).isTrue();
        assertThat(columnExists(jdbc, "t_session_compaction_checkpoint",
                "summary_id_watermark")).isTrue();
        assertThat(columnExists(jdbc, "t_tool_result_archive", "canonical_payload_hash")).isTrue();
    }

    @Test
    @Order(2)
    void migrate_populatedV195_preservesRowsAndAppliesSafeDefaults() {
        assertThat(migratorJdbc.queryForObject(
                "SELECT history_epoch FROM t_session WHERE id='history-upgrade'", Long.class)).isZero();
        assertThat(migratorJdbc.queryForObject(
                "SELECT runtime_snapshot_json FROM t_session_compaction_checkpoint WHERE id='history-cp'",
                String.class)).isNull();
        assertThat(migratorJdbc.queryForObject(
                "SELECT sidecar_watermark FROM t_session_compaction_checkpoint WHERE id='history-cp'",
                Long.class)).isNull();
        assertThat(migratorJdbc.queryForObject(
                "SELECT summary_id_watermark FROM t_session_compaction_checkpoint WHERE id='history-cp'",
                Long.class)).isNull();
        assertThat(migratorJdbc.queryForObject(
                "SELECT COUNT(*) FROM t_session_message WHERE session_id='history-upgrade'", Long.class)).isOne();
        assertThat(migratorJdbc.queryForObject(
                "SELECT COUNT(*) FROM t_tool_result_archive WHERE archive_id='legacy-history-archive'",
                Long.class)).isOne();
        assertThat(migratorJdbc.queryForObject(
                "SELECT canonical_payload_hash FROM t_tool_result_archive WHERE archive_id='legacy-history-archive'",
                String.class)).isNull();
        assertThat(migratorJdbc.queryForObject(
                "SELECT session_message_id FROM t_tool_result_archive WHERE archive_id='legacy-history-archive'",
                Long.class)).isNull();
    }

    @Test
    @Order(3)
    void sessionAndMessageColumns_enforceEpochLoopAndWriteBatchShapes() {
        assertThatThrownBy(() -> migratorJdbc.update(
                "UPDATE t_session SET history_epoch=-1 WHERE id='history-upgrade'"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> migratorJdbc.update(
                "UPDATE t_session SET active_loop_id='loop-1' WHERE id='history-upgrade'"))
                .isInstanceOf(Exception.class);

        migratorJdbc.update("""
                UPDATE t_session
                SET active_loop_id='loop-1', loop_fence=1,
                    loop_owner_instance_id='instance-1', loop_lease_until=NOW() + INTERVAL '1 minute'
                WHERE id='history-upgrade'
                """);
        assertThat(migratorJdbc.queryForObject(
                "SELECT loop_fence FROM t_session WHERE id='history-upgrade'", Long.class)).isOne();

        assertThatThrownBy(() -> migratorJdbc.update("""
                UPDATE t_session_message SET write_batch_id=? WHERE id=?
                """, UUID.randomUUID().toString(), messageId("history-upgrade")))
                .isInstanceOf(Exception.class);

        String batchId = UUID.randomUUID().toString();
        migratorJdbc.update("""
                UPDATE t_session_message SET write_batch_id=?, write_batch_ordinal=0 WHERE id=?
                """, batchId, messageId("history-upgrade"));
        insertMessage("history-upgrade", 1, "batch-duplicate");
        assertThatThrownBy(() -> migratorJdbc.update("""
                UPDATE t_session_message SET write_batch_id=?, write_batch_ordinal=0
                WHERE session_id='history-upgrade' AND seq_no=1
                """, batchId)).isInstanceOf(Exception.class);
    }

    @Test
    @Order(4)
    void attemptConstraints_rejectInvalidStatePartialExecutionAndSecondUnresolvedAttempt() {
        long assistantMessageId = insertMessage("history-upgrade", 10, "attempt-assistant-1");
        UUID openStep = UUID.randomUUID();
        long openAttempt = insertIntentAttempt("history-upgrade", assistantMessageId, openStep,
                "INTENT_COMMITTED");
        migratorJdbc.update("""
                UPDATE t_session_tool_attempt
                SET state='UNCERTAIN_PENDING_RESOLUTION', execution_loop_id='loop-uncertain',
                    execution_fence=1, execution_owner_instance_id='instance-uncertain',
                    execution_generation=1, claim_request_id=?, claimed_at=NOW(),
                    execution_lease_until=NOW() + INTERVAL '1 minute'
                WHERE id=?
                """, UUID.randomUUID(), openAttempt);

        assertThat(openAttempt).isPositive();
        assertThatThrownBy(() -> migratorJdbc.update("""
                UPDATE t_session_tool_attempt SET state='INVALID' WHERE id=?
                """, openAttempt)).isInstanceOf(Exception.class);
        insertSession(migratorJdbc, "history-invalid-attempt");
        long malformedAssistant = insertMessage("history-invalid-attempt", 0, "malformed-attempt");
        assertThatThrownBy(() -> migratorJdbc.update("""
                INSERT INTO t_session_tool_attempt
                    (session_id,step_id,history_epoch,origin_loop_id,origin_fence,
                     assistant_message_id,assistant_payload_hash,
                     pre_intent_max_message_id,pre_intent_max_seq,
                     manifest_json,manifest_hash,replay_safety,state,
                     execution_loop_id,execution_generation)
                VALUES ('history-invalid-attempt', ?, 0, 'origin-loop', 0, ?, repeat('1',64),
                        -1, -1, '[]', repeat('2',64), 'UNKNOWN', 'EXECUTING', 'loop-x', 1)
                """, UUID.randomUUID(), malformedAssistant)).isInstanceOf(Exception.class);

        insertSession(migratorJdbc, "history-half-frontier");
        long halfFrontierAssistant = insertMessage("history-half-frontier", 0, "half-frontier");
        assertSqlState("23514", () -> migratorJdbc.update("""
                INSERT INTO t_session_tool_attempt
                    (session_id,step_id,history_epoch,origin_loop_id,origin_fence,
                     assistant_message_id,assistant_payload_hash,
                     pre_intent_max_message_id,pre_intent_max_seq,
                     manifest_json,manifest_hash,replay_safety,state)
                VALUES ('history-half-frontier', ?, 0, 'origin-loop', 0, ?, repeat('1',64),
                        -1, 0, '[]', repeat('2',64), 'UNKNOWN', 'INTENT_COMMITTED')
                """, UUID.randomUUID(), halfFrontierAssistant));

        insertSession(migratorJdbc, "history-zero-frontier");
        long zeroFrontierAssistant = insertMessage("history-zero-frontier", 0, "zero-frontier");
        assertSqlState("23514", () -> migratorJdbc.update("""
                INSERT INTO t_session_tool_attempt
                    (session_id,step_id,history_epoch,origin_loop_id,origin_fence,
                     assistant_message_id,assistant_payload_hash,
                     pre_intent_max_message_id,pre_intent_max_seq,
                     manifest_json,manifest_hash,replay_safety,state)
                VALUES ('history-zero-frontier', ?, 0, 'origin-loop', 0, ?, repeat('1',64),
                        0, 0, '[]', repeat('2',64), 'UNKNOWN', 'INTENT_COMMITTED')
                """, UUID.randomUUID(), zeroFrontierAssistant));

        insertSession(migratorJdbc, "history-cross-message-a");
        insertSession(migratorJdbc, "history-cross-message-b");
        long otherSessionAssistant = insertMessage(
                "history-cross-message-b", 0, "cross-session-assistant");
        assertSqlState("23503", () -> migratorJdbc.update("""
                INSERT INTO t_session_tool_attempt
                    (session_id,step_id,history_epoch,origin_loop_id,origin_fence,
                     assistant_message_id,assistant_payload_hash,
                     pre_intent_max_message_id,pre_intent_max_seq,
                     manifest_json,manifest_hash,replay_safety,state)
                VALUES ('history-cross-message-a', ?, 0, 'origin-loop', 0, ?, repeat('1',64),
                        -1, -1, '[]', repeat('2',64), 'UNKNOWN', 'INTENT_COMMITTED')
                """, UUID.randomUUID(), otherSessionAssistant));

        long secondAssistant = insertMessage("history-upgrade", 11, "attempt-assistant-2");
        assertThatThrownBy(() -> insertIntentAttempt(
                "history-upgrade", secondAssistant, UUID.randomUUID(), "WAITING_USER"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @Order(5)
    void postActionShape_bindsResolutionResultAndSingleRunClaim() {
        long attemptId = migratorJdbc.queryForObject("""
                SELECT id FROM t_session_tool_attempt
                WHERE session_id='history-upgrade' AND state='UNCERTAIN_PENDING_RESOLUTION'
                """, Long.class);
        UUID resolutionRequestId = UUID.randomUUID();
        UUID resultBatchId = UUID.randomUUID();

        assertThatThrownBy(() -> migratorJdbc.update("""
                UPDATE t_session_tool_attempt SET post_action_state='PENDING' WHERE id=?
                """, attemptId)).isInstanceOf(Exception.class);

        migratorJdbc.update("""
                UPDATE t_session_tool_attempt
                SET state='RESOLVED_UNKNOWN',
                    execution_loop_id='loop-resolution', execution_fence=2,
                    execution_owner_instance_id='instance-resolution', execution_generation=1,
                    claim_request_id=?, claimed_at=NOW(), execution_lease_until=NOW() + INTERVAL '1 minute',
                    result_batch_id=?, result_execution_generation=1, result_execution_fence=2,
                    archive_preparation_state='PENDING', archive_total_count=1,
                    post_action_state='PENDING', post_action_resolution_request_id=?,
                    post_action_result_batch_id=?, post_action_kind='CONTINUE_CURRENT_TIMELINE'
                WHERE id=?
                """, UUID.randomUUID(), resultBatchId, resolutionRequestId, resultBatchId, attemptId);

        assertThatThrownBy(() -> migratorJdbc.update("""
                UPDATE t_session_tool_attempt
                SET post_action_state='CLAIMED', post_action_claim_request_id=?,
                    post_action_loop_id='loop-claim'
                WHERE id=?
                """, UUID.randomUUID(), attemptId)).isInstanceOf(Exception.class);

        migratorJdbc.update("""
                UPDATE t_session_tool_attempt
                SET post_action_state='CLAIMED', post_action_claim_request_id=?,
                    post_action_loop_id='loop-claim', post_action_fence=3
                WHERE id=?
                """, UUID.randomUUID(), attemptId);
        assertThat(migratorJdbc.queryForObject(
                "SELECT post_action_state FROM t_session_tool_attempt WHERE id=?",
                String.class, attemptId)).isEqualTo("CLAIMED");
    }

    @Test
    @Order(6)
    void runtimeRole_auditIsInsertSelectOnlyAndAttemptPruningKeepsAudit() {
        assertThat(runtimeJdbc.queryForObject("SELECT current_user", String.class))
                .isEqualTo(RUNTIME_ROLE);
        assertThat(migratorJdbc.queryForObject("""
                SELECT tableowner FROM pg_tables
                WHERE schemaname='public'
                  AND tablename='t_session_tool_attempt_resolution_audit'
                """, String.class)).isEqualTo(MIGRATOR_ROLE);
        assertThat(migratorJdbc.queryForObject("""
                SELECT has_table_privilege(?, 'public.t_session_tool_attempt_resolution_audit', 'SELECT')
                """, Boolean.class, RUNTIME_ROLE)).isTrue();
        assertThat(migratorJdbc.queryForObject("""
                SELECT has_table_privilege(?, 'public.t_session_tool_attempt_resolution_audit', 'INSERT')
                """, Boolean.class, RUNTIME_ROLE)).isTrue();
        assertThat(migratorJdbc.queryForObject("""
                SELECT has_table_privilege(?, 'public.t_session_tool_attempt_resolution_audit', 'UPDATE')
                """, Boolean.class, RUNTIME_ROLE)).isFalse();
        assertThat(migratorJdbc.queryForObject("""
                SELECT has_table_privilege(?, 'public.t_session_tool_attempt_resolution_audit', 'DELETE')
                """, Boolean.class, RUNTIME_ROLE)).isFalse();
        assertThat(migratorJdbc.queryForObject("""
                SELECT has_table_privilege(?, 'public.t_session_tool_attempt_resolution_audit', 'TRUNCATE')
                """, Boolean.class, RUNTIME_ROLE)).isFalse();

        long attemptId = migratorJdbc.queryForObject("""
                SELECT id FROM t_session_tool_attempt WHERE session_id='history-upgrade'
                """, Long.class);
        UUID resolutionRequestId = migratorJdbc.queryForObject("""
                SELECT post_action_resolution_request_id FROM t_session_tool_attempt WHERE id=?
                """, UUID.class, attemptId);
        UUID resultBatchId = migratorJdbc.queryForObject("""
                SELECT post_action_result_batch_id FROM t_session_tool_attempt WHERE id=?
                """, UUID.class, attemptId);

        runtimeJdbc.update("""
                INSERT INTO t_session_tool_attempt_resolution_audit
                    (resolution_request_id, session_id, attempt_id, step_id, history_epoch,
                     execution_generation, execution_fence, actor_id, actor_authority,
                     reason_hash, action, inbox_dispositions_json, result_batch_id, outcome_state)
                SELECT ?, session_id, id, step_id, history_epoch, execution_generation, execution_fence,
                       1, 'OWNER', repeat('a', 64), 'CONTINUE_CURRENT_TIMELINE', '[]', ?,
                       'RESOLVED_UNKNOWN'
                FROM t_session_tool_attempt WHERE id=?
                """, resolutionRequestId, resultBatchId, attemptId);

        assertThat(runtimeJdbc.queryForObject("""
                SELECT COUNT(*) FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id=?
                """, Long.class, resolutionRequestId)).isOne();

        // A restore-style timeline rewrite may delete message rows after attempt pruning;
        // the scalar audit identity must remain independent of both lifecycles.
        runtimeJdbc.update("DELETE FROM t_session_message WHERE session_id='history-upgrade'");
        assertThat(runtimeJdbc.queryForObject("""
                SELECT COUNT(*) FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id=?
                """, Long.class, resolutionRequestId)).isOne();
        assertPrivilegeDenied(() -> runtimeJdbc.update("""
                UPDATE t_session_tool_attempt_resolution_audit
                SET outcome_state='RESOLVED_UNKNOWN' WHERE resolution_request_id=?
                """, resolutionRequestId));
        assertPrivilegeDenied(() -> runtimeJdbc.update("""
                DELETE FROM t_session_tool_attempt_resolution_audit WHERE resolution_request_id=?
                """, resolutionRequestId));
        assertPrivilegeDenied(() -> runtimeJdbc.execute(
                "TRUNCATE TABLE t_session_tool_attempt_resolution_audit"));

        runtimeJdbc.update("DELETE FROM t_session_tool_attempt WHERE id=?", attemptId);
        assertThat(runtimeJdbc.queryForObject("""
                SELECT COUNT(*) FROM t_session_tool_attempt_resolution_audit
                WHERE resolution_request_id=?
                """, Long.class, resolutionRequestId)).isOne();
    }

    @Test
    @Order(7)
    void wholeSessionDelete_isOnlyAuditRetentionExceptionAndCascadesInbox() {
        insertSession("history-delete");
        runtimeJdbc.update("""
                INSERT INTO t_session_message_inbox (inbox_id,session_id,user_id,message_json)
                VALUES (?, 'history-delete', 1, '{"role":"USER","content":"queued"}')
                """, UUID.randomUUID());
        runtimeJdbc.update("""
                INSERT INTO t_session_tool_attempt_resolution_audit
                    (resolution_request_id,session_id,attempt_id,step_id,history_epoch,
                     execution_generation,execution_fence,actor_id,actor_authority,reason_hash,
                     action,inbox_dispositions_json,result_batch_id,outcome_state)
                VALUES (?, 'history-delete', 999999, ?, 0, 1, 1, 1, 'OWNER', repeat('b',64),
                        'PREPARE_RESTORE', '[]', ?, 'RESOLVED_UNKNOWN')
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        runtimeJdbc.update("DELETE FROM t_session WHERE id='history-delete'");

        assertThat(migratorJdbc.queryForObject(
                "SELECT COUNT(*) FROM t_session_tool_attempt_resolution_audit WHERE session_id='history-delete'",
                Long.class)).isZero();
        assertThat(migratorJdbc.queryForObject(
                "SELECT COUNT(*) FROM t_session_message_inbox WHERE session_id='history-delete'",
                Long.class)).isZero();
    }

    @Test
    @Order(8)
    void archiveOccurrenceConstraints_keepLegacyAndAllowSameToolUseAcrossOccurrences() {
        long firstMessage = insertMessage("history-upgrade", 20, "archive-first");
        long secondMessage = insertMessage("history-upgrade", 21, "archive-second");
        insertArchive("archive-occ-1", firstMessage, 0, "same-tool", "c");
        insertArchive("archive-occ-2", secondMessage, 0, "same-tool", "d");

        assertThat(migratorJdbc.queryForObject("""
                SELECT COUNT(*) FROM t_tool_result_archive
                WHERE session_id='history-upgrade' AND tool_use_id='same-tool'
                """, Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> insertArchive(
                "archive-occ-duplicate", firstMessage, 0, "another-tool", "e"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> migratorJdbc.update("""
                UPDATE t_tool_result_archive SET canonical_payload_hash='ABC'
                WHERE archive_id='archive-occ-1'
                """)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> migratorJdbc.update("""
                INSERT INTO t_tool_result_archive
                    (archive_id,session_id,tool_use_id,original_chars,content)
                VALUES ('legacy-history-archive-duplicate','history-upgrade','legacy-tool',8,'legacy2')
                """)).isInstanceOf(Exception.class);
    }

    private static void migrateTo(DataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("etl_mode", "off", "etl_trace_span_mode", "off"))
                .validateOnMigrate(false)
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private static void insertV195Fixture(JdbcTemplate jdbc) {
        insertSession(jdbc, "history-upgrade");
        jdbc.update("""
                INSERT INTO t_session_message
                    (session_id,seq_no,role,msg_type,content_json,metadata_json)
                VALUES ('history-upgrade',0,'USER','NORMAL','[]','{}')
                """);
        long legacyMessageId = jdbc.queryForObject("""
                SELECT id FROM t_session_message
                WHERE session_id='history-upgrade' AND seq_no=0
                """, Long.class);
        jdbc.update("""
                INSERT INTO t_session_compaction_checkpoint
                    (id,session_id,boundary_seq_no,reason)
                VALUES ('history-cp','history-upgrade',0,'manual')
                """);
        jdbc.update("""
                INSERT INTO t_tool_result_archive
                    (archive_id,session_id,session_message_id,tool_use_id,original_chars,content)
                VALUES ('legacy-history-archive','history-upgrade',?,'legacy-tool',7,'legacy!')
                """, legacyMessageId);
    }

    private static void insertSession(String sessionId) {
        insertSession(runtimeJdbc, sessionId);
    }

    private static void insertSession(JdbcTemplate jdbc, String sessionId) {
        jdbc.update("""
                INSERT INTO t_session (id,user_id,agent_id,title) VALUES (?,1,1,'history test')
                """, sessionId);
    }

    private static long insertMessage(String sessionId, long seqNo, String content) {
        migratorJdbc.update("""
                INSERT INTO t_session_message
                    (session_id,seq_no,role,msg_type,content_json,metadata_json)
                VALUES (? ,?,'ASSISTANT','NORMAL',?,'{}')
                """, sessionId, seqNo, "[\"" + content + "\"]");
        return migratorJdbc.queryForObject("""
                SELECT id FROM t_session_message WHERE session_id=? AND seq_no=?
                """, Long.class, sessionId, seqNo);
    }

    private static long messageId(String sessionId) {
        return migratorJdbc.queryForObject("""
                SELECT id FROM t_session_message WHERE session_id=? ORDER BY seq_no LIMIT 1
                """, Long.class, sessionId);
    }

    private static long insertIntentAttempt(
            String sessionId, long assistantMessageId, UUID stepId, String state) {
        return migratorJdbc.queryForObject("""
                INSERT INTO t_session_tool_attempt
                    (session_id,step_id,history_epoch,origin_loop_id,origin_fence,
                     assistant_message_id,assistant_payload_hash,
                     pre_intent_max_message_id,pre_intent_max_seq,
                     manifest_json,manifest_hash,replay_safety,state)
                VALUES (?, ?, 0, 'origin-loop', 0, ?, repeat('1',64), -1, -1,
                        '[]', repeat('2',64), 'UNKNOWN', ?)
                RETURNING id
                """, Long.class, sessionId, stepId, assistantMessageId, state);
    }

    private static void insertArchive(
            String archiveId, long messageId, int blockIndex, String toolUseId, String hashChar) {
        migratorJdbc.update("""
                INSERT INTO t_tool_result_archive
                    (archive_id,session_id,session_message_id,block_index,tool_use_id,
                     original_chars,content,canonical_payload_hash,payload_hash_version)
                VALUES (?, 'history-upgrade', ?, ?, ?, 7, 'payload', repeat(?,64), 1)
                """, archiveId, messageId, blockIndex, toolUseId, hashChar);
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema='public' AND table_name=? AND column_name=?
                )
                """, Boolean.class, table, column));
    }

    private static void assertPrivilegeDenied(Runnable operation) {
        assertSqlState("42501", operation);
    }

    private static void assertSqlState(String expectedSqlState, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(Exception.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .rootCause()
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo(expectedSqlState);
    }

    private static DataSource roleDataSource(String database, String username) {
        String url = "jdbc:postgresql://localhost:" + postgres.getPort() + "/" + database;
        return dataSource(url, username, "");
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
