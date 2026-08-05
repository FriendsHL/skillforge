package com.skillforge.server.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTaskMigrationIT {
    private static EmbeddedPostgres postgres; private static Connection connection; private static JdbcTemplate jdbc;
    @BeforeAll static void start() throws Exception {
        postgres=EmbeddedPostgres.builder().start(); connection=postgres.getPostgresDatabase().getConnection();
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
    }
    @AfterAll static void stop() throws SQLException, IOException { if(connection!=null)connection.close(); if(postgres!=null)postgres.close(); }
    @BeforeEach void baseline(){
        jdbc.execute("DROP TABLE IF EXISTS t_session_task_dependency CASCADE"); jdbc.execute("DROP TABLE IF EXISTS t_session_task CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS t_agent CASCADE"); jdbc.execute("DROP TABLE IF EXISTS t_session CASCADE");
        jdbc.execute("CREATE TABLE t_session(id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE t_agent(id BIGINT PRIMARY KEY, name TEXT, system_prompt TEXT, tool_ids TEXT, config TEXT, updated_at TIMESTAMPTZ)");
        jdbc.update("INSERT INTO t_session(id) VALUES ('s1'),('s2')");
        jdbc.update("INSERT INTO t_agent(id,name,system_prompt,tool_ids,config) VALUES (1,'Custom',NULL,?,?), (2,'Open',NULL,NULL,NULL), (3,'Main Assistant',?,?,?)",
                "[\"Bash\",\"TodoWrite\",\"Custom\"]","{\"tool_ids\":[\"Bash\",\"TodoWrite\",\"Custom\"],\"keep\":true}", v188Prompt(),
                "[\"Bash\",\"TodoWrite\",\"CustomMain\"]", "{\"tool_ids\":[\"Bash\",\"TodoWrite\",\"CustomMain\"],\"keep\":true}");
        ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V189__create_session_tasks.sql"));
    }
    @Test void enforcesOwnershipDependencyAndMigratesOnlyBuiltInMainAssistant(){
        String custom=jdbc.queryForObject("SELECT tool_ids FROM t_agent WHERE id=1",String.class);
        assertThat(custom).isEqualTo("[\"Bash\",\"TodoWrite\",\"Custom\"]");
        assertThat(jdbc.queryForObject("SELECT config FROM t_agent WHERE id=1",String.class))
                .isEqualTo("{\"tool_ids\":[\"Bash\",\"TodoWrite\",\"Custom\"],\"keep\":true}");
        assertThat(jdbc.queryForObject("SELECT tool_ids FROM t_agent WHERE id=2",String.class)).isNull();
        String migrated=jdbc.queryForObject("SELECT tool_ids FROM t_agent WHERE id=3",String.class);
        assertThat(migrated).contains("Bash","CustomMain","TaskCreate","TaskUpdate","TaskGet","TaskList").doesNotContain("TodoWrite");
        assertThat(jdbc.queryForObject("SELECT system_prompt FROM t_agent WHERE id=3",String.class))
                .contains("## Task 行为矩阵","普通追问","增量更新对应 Task","新的 pending Task","自动退回 pending","重新设为 pending","新建 Task","优先继续当前 in_progress","不把未完成 Task 伪装成 completed","不得把最后一个 Tool error 当成任务结论");

        insertTask("a","s1",null,"in_progress");
        assertThatThrownBy(()->insertTask("b","s1",null,"in_progress")).isInstanceOf(Exception.class);
        insertTask("b","s1","worker","pending"); insertTask("c","s2",null,"pending");
        jdbc.update("INSERT INTO t_session_task_dependency(session_id,task_id,blocked_by_task_id) VALUES('s1','b','a')");
        assertThatThrownBy(()->jdbc.update("INSERT INTO t_session_task_dependency(session_id,task_id,blocked_by_task_id) VALUES('s1','b','c')"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO t_session_task_dependency(session_id,task_id,blocked_by_task_id) VALUES('s1','b','b')"))
                .isInstanceOf(Exception.class);
        jdbc.update("DELETE FROM t_session WHERE id='s1'");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_session_task WHERE session_id='s1'",Long.class)).isZero();
    }
    @Test void customMainPromptIsNotOverwritten() {
        resetAndMigrate("my custom prompt");
        assertThat(jdbc.queryForObject("SELECT system_prompt FROM t_agent WHERE id=3",String.class))
                .isEqualTo("my custom prompt");
    }
    private void insertTask(String id,String session,String owner,String status){
        jdbc.update("INSERT INTO t_session_task(id,session_id,user_id,subject,description,active_form,status,owner) VALUES(?,?,1,'S','D','A',?,?)",
                id,session,status,owner);
    }
    private static String v188Prompt(){return """
            你是 SkillForge 的 Main Assistant，负责理解用户目标并协调完成当前 Session 的任务。

            ## 核心职责
            1. 明确目标和成功标准，必要时拆分为可验证的步骤。
            2. 自己完成一般任务；只有子任务边界清晰且确有并行或专业收益时才委派。
            3. 整合工具与子 Agent 的结果，向用户给出结论、验证证据和剩余风险。
            4. 需要历史信息时按需使用 Memory 检索，不把短期会话摘要当作长期事实。

            ## 决策原则
            - 用户要求分析或评估时，先报告判断，不擅自修改。
            - 信息足够就推进；存在会显著改变结果的歧义时再确认。
            - 遵循用户最新指令；已确认且未被后续反馈改变的决定不重复推导。需要权衡时给出明确推荐。
            - 以当前 Session 的目标为中心，不重复平台级规则或工具手册。
            """.trim();}

    private void resetAndMigrate(String mainPrompt) {
        jdbc.execute("DROP TABLE IF EXISTS t_session_task_dependency CASCADE"); jdbc.execute("DROP TABLE IF EXISTS t_session_task CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS t_agent CASCADE"); jdbc.execute("DROP TABLE IF EXISTS t_session CASCADE");
        jdbc.execute("CREATE TABLE t_session(id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE t_agent(id BIGINT PRIMARY KEY, name TEXT, system_prompt TEXT, tool_ids TEXT, config TEXT, updated_at TIMESTAMPTZ)");
        jdbc.update("INSERT INTO t_session(id) VALUES ('s1')");
        jdbc.update("INSERT INTO t_agent(id,name,system_prompt,tool_ids,config) VALUES(3,'Main Assistant',?,'[]','{}')",mainPrompt);
        ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V189__create_session_tasks.sql"));
    }
}
