package com.skillforge.server.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V188 — restore missing Main Assistant prompt governance")
class V188RestoreMainAssistantPromptTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> EXPECTED_RULE_IDS = List.of(
            "sandbox-file-scope",
            "validate-input",
            "minimal-change",
            "no-mock-in-prod",
            "test-after-change",
            "state-assumptions-explicit",
            "simplicity-first-no-speculation",
            "clean-only-own-orphans",
            "goal-driven-verify-loop");

    @Test
    @DisplayName("V185 默认配置恢复缺失规则并补充 Main 决策原则")
    void defaultV185Shape_isRestored() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = pg.getPostgresDatabase();
            migrateTo187(dataSource);

            AgentPromptState before = readMainAssistant(dataSource);
            assertThat(parseRuleIds(before.behaviorRules())).isEmpty();

            migrateLatest(dataSource);

            AgentPromptState after = readMainAssistant(dataSource);
            assertThat(parseRuleIds(after.behaviorRules())).containsExactlyElementsOf(EXPECTED_RULE_IDS);
            assertThat(after.systemPrompt())
                    .contains("遵循用户最新指令")
                    .contains("未被后续反馈改变的决定不重复推导")
                    .contains("需要权衡时给出明确推荐");
        }
    }

    @Test
    @DisplayName("用户自定义 Behavior Rules 时不覆盖，但默认 Main Prompt 仍可修复")
    void customBehaviorRules_arePreserved() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = pg.getPostgresDatabase();
            migrateTo187(dataSource);
            String customRules = """
                    {"builtinRuleIds":["preserve-existing-style"],"customRules":[{"severity":"MUST","text":"custom"}]}
                    """.trim();
            updateMainAssistant(dataSource, "behavior_rules", customRules);

            migrateLatest(dataSource);

            AgentPromptState after = readMainAssistant(dataSource);
            assertThat(OBJECT_MAPPER.readTree(after.behaviorRules()))
                    .isEqualTo(OBJECT_MAPPER.readTree(customRules));
            assertThat(after.systemPrompt()).contains("遵循用户最新指令");
        }
    }

    @Test
    @DisplayName("用户自定义 Main Prompt 时不覆盖，但默认空规则仍可修复")
    void customSystemPrompt_isPreserved() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = pg.getPostgresDatabase();
            migrateTo187(dataSource);
            updateMainAssistant(dataSource, "system_prompt", "my custom main prompt");

            migrateLatest(dataSource);

            AgentPromptState after = readMainAssistant(dataSource);
            assertThat(after.systemPrompt()).isEqualTo("my custom main prompt");
            assertThat(parseRuleIds(after.behaviorRules())).containsExactlyElementsOf(EXPECTED_RULE_IDS);
        }
    }

    @Test
    @DisplayName("带额外顶层字段的 Behavior Rules 视为用户配置并完整保留")
    void behaviorRulesWithExtraTopLevelField_arePreserved() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = pg.getPostgresDatabase();
            migrateTo187(dataSource);
            String customizedRules = """
                    {
                      "builtinRuleIds": [],
                      "customRules": [
                        {
                          "severity": "MUST",
                          "text": "你是主 Agent：负责澄清目标、拆解计划、选择是否委派、整合结果并给出最终回复。"
                        },
                        {
                          "severity": "SHOULD",
                          "text": "仅当子任务边界清晰且确有并行或专业收益时委派；最终回复合并关键发现、执行动作、验证结果和剩余风险。"
                        }
                      ],
                      "metadata": {"owner":"user"}
                    }
                    """.trim();
            updateMainAssistant(dataSource, "behavior_rules", customizedRules);

            migrateLatest(dataSource);

            AgentPromptState after = readMainAssistant(dataSource);
            assertThat(after.behaviorRules()).isEqualTo(customizedRules);
            assertThat(after.systemPrompt()).contains("遵循用户最新指令");
        }
    }

    @Test
    @DisplayName("非法 JSON Behavior Rules 不阻断迁移且原值保持不变")
    void invalidBehaviorRules_arePreservedWithoutBlockingMigration() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = pg.getPostgresDatabase();
            migrateTo187(dataSource);
            String invalidRules = "{invalid-json";
            updateMainAssistant(dataSource, "behavior_rules", invalidRules);

            migrateLatest(dataSource);

            AgentPromptState after = readMainAssistant(dataSource);
            assertThat(after.behaviorRules()).isEqualTo(invalidRules);
            assertThat(after.systemPrompt()).contains("遵循用户最新指令");
        }
    }

    private static void migrateTo187(DataSource dataSource) {
        flyway(dataSource, MigrationVersion.fromVersion("187")).migrate();
    }

    private static void migrateLatest(DataSource dataSource) {
        flyway(dataSource, null).migrate();
    }

    private static Flyway flyway(DataSource dataSource, MigrationVersion target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "etl_mode", "off",
                        "etl_trace_span_mode", "off"));
        if (target != null) config.target(target);
        return config.load();
    }

    private static List<String> parseRuleIds(String json) throws Exception {
        JsonNode ids = OBJECT_MAPPER.readTree(json).path("builtinRuleIds");
        return OBJECT_MAPPER.convertValue(ids,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static AgentPromptState readMainAssistant(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT system_prompt, behavior_rules FROM t_agent WHERE id = 3 AND name = 'Main Assistant'")) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new AgentPromptState(result.getString(1), result.getString(2));
            }
        }
    }

    private static void updateMainAssistant(DataSource dataSource, String column, String value) throws Exception {
        if (!List.of("system_prompt", "behavior_rules").contains(column)) {
            throw new IllegalArgumentException("Unsupported column: " + column);
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE t_agent SET " + column + " = ? WHERE id = 3 AND name = 'Main Assistant'")) {
            statement.setString(1, value);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private record AgentPromptState(String systemPrompt, String behaviorRules) {}
}
