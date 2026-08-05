package com.skillforge.server.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class V190RepairSystemAgentToolContractsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void migration_repairsToolExposureAndSubAgentDispatchContractWithoutTouchingOtherAgents()
            throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
             Connection connection = postgres.getPostgresDatabase().getConnection()) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("""
                    CREATE TABLE t_agent (
                        id BIGINT PRIMARY KEY,
                        name TEXT NOT NULL,
                        system_prompt TEXT,
                        tool_ids TEXT,
                        config TEXT,
                        updated_at TIMESTAMPTZ
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE t_scheduled_task (
                        id BIGSERIAL PRIMARY KEY,
                        name TEXT NOT NULL,
                        prompt_template TEXT,
                        updated_at TIMESTAMPTZ
                    )
                    """);
            jdbc.update("""
                    INSERT INTO t_agent(id,name,system_prompt,tool_ids,config) VALUES
                    (6,'memory-curator',?, ?, ?),
                    (7,'session-annotator',?, ?, ?),
                    (8,'custom-agent','custom prompt','[\"GetTrace\"]','{\"keep\":true}')
                    """,
                    memoryPrompt(),
                    "[\"ListActiveUsers\",\"ListMemoryCandidates\",\"ListRecentSessionTranscripts\","
                            + "\"ClusterMemories\",\"CreateMemoryProposal\",\"SubAgent\"]",
                    "{\"temperature\":0.3}",
                    "Call SessionAnnotationRead(sessionId), GetTrace, and AnnotateSession.",
                    "[\"DetectSignalAnnotations\",\"SpanBehaviorStats\",\"GetTrace\","
                            + "\"AnnotateSession\",\"RecomputeClusters\"]",
                    "{\"temperature\":0.2}");
            jdbc.update("""
                    INSERT INTO t_scheduled_task(name,prompt_template)
                    VALUES ('memory-curator nightly', ?)
                    """, "Run memory dreaming. Use ListActiveUsers, then SubAgent per user.");

            runMigration(connection);
            runMigration(connection);

            JsonNode annotatorTools = OBJECT_MAPPER.readTree(jdbc.queryForObject(
                    "SELECT tool_ids FROM t_agent WHERE name='session-annotator'", String.class));
            assertThat(asStrings(annotatorTools))
                    .containsExactly(
                            "DetectSignalAnnotations", "SpanBehaviorStats", "GetTrace",
                            "AnnotateSession", "RecomputeClusters", "SessionAnnotationRead");
            JsonNode annotatorConfig = config(jdbc, "session-annotator");
            assertThat(asStrings(annotatorConfig.path("tool_ids")))
                    .contains("SessionAnnotationRead");
            assertThat(asStrings(annotatorConfig.path("required_tool_ids")))
                    .containsExactlyElementsOf(asStrings(annotatorTools));

            JsonNode curatorConfig = config(jdbc, "memory-curator");
            assertThat(asStrings(curatorConfig.path("required_tool_ids")))
                    .containsExactly(
                            "ListActiveUsers", "ListMemoryCandidates", "ListRecentSessionTranscripts",
                            "ClusterMemories", "CreateMemoryProposal", "SubAgent");
            assertThat(jdbc.queryForObject(
                    "SELECT system_prompt FROM t_agent WHERE name='memory-curator'", String.class))
                    .contains("SubAgent(action=\"dispatch\", agentName=\"memory-curator\"");
            assertThat(jdbc.queryForObject(
                    "SELECT prompt_template FROM t_scheduled_task WHERE name='memory-curator nightly'",
                    String.class))
                    .contains("agentName=\"memory-curator\"");

            assertThat(jdbc.queryForObject(
                    "SELECT config FROM t_agent WHERE name='custom-agent'", String.class))
                    .isEqualTo("{\"keep\":true}");
        }
    }

    private static void runMigration(Connection connection) {
        ScriptUtils.executeSqlScript(
                connection,
                new ClassPathResource("db/migration/V190__repair_system_agent_tool_contracts.sql"));
    }

    private static JsonNode config(JdbcTemplate jdbc, String agentName) throws Exception {
        return OBJECT_MAPPER.readTree(jdbc.queryForObject(
                "SELECT config FROM t_agent WHERE name=?", String.class, agentName));
    }

    private static List<String> asStrings(JsonNode array) {
        return OBJECT_MAPPER.convertValue(
                array,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static String memoryPrompt() {
        return """
                ## Workflow

                1. Call `ListActiveUsers` to get userIds with recent activity.
                2. For each userId, call `SubAgent` and dispatch one sub-session:
                   `Run memory dreaming for userId=<id>. Use ListMemoryCandidates, ListRecentSessionTranscripts, ClusterMemories, and CreateMemoryProposal to produce evidence-backed proposals.`
                3. Return a short summary.
                """;
    }
}
