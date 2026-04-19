package com.skillforge.server.init;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Seeds a "Code Agent" template on first startup (if no agent named "Code Agent" exists).
 * The Code Agent has a curated skill pack for writing, testing, reviewing, and registering
 * hook methods — both script (bash/node) and compiled (Java).
 */
@Component
@Order(200)
public class CodeAgentInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CodeAgentInitializer.class);

    private static final String AGENT_NAME = "Code Agent";

    private static final List<String> CODE_SKILL_PACK = List.of(
            "Bash", "FileRead", "FileWrite", "Glob", "Grep",
            "CodeSandbox", "CodeReview",
            "RegisterScriptMethod", "RegisterCompiledMethod"
    );

    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public CodeAgentInitializer(AgentRepository agentRepository, ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (agentRepository.existsByName(AGENT_NAME)) {
            log.info("CodeAgentInitializer: skip, '{}' already exists", AGENT_NAME);
            return;
        }

        AgentEntity agent = new AgentEntity();
        agent.setName(AGENT_NAME);
        agent.setDescription("能编码的 Agent：自主编写、测试、审查并注册 Hook 方法（bash/node 脚本或 Java 类），提升平台能力。");
        agent.setModelId("claude");
        agent.setSystemPrompt(buildSystemPrompt());
        try {
            agent.setSkillIds(objectMapper.writeValueAsString(CODE_SKILL_PACK));
            agent.setConfig(objectMapper.writeValueAsString(Map.of("temperature", 0.3, "maxTokens", 8192)));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize code agent config, using fallback", e);
            agent.setSkillIds("[]");
            agent.setConfig("{}");
        }
        agent.setOwnerId(null);
        agent.setPublic(true);
        agent.setStatus("active");
        agent.setExecutionMode("ask");

        try {
            AgentEntity saved = agentRepository.save(agent);
            log.info("CodeAgentInitializer: created '{}' id={}, skillCount={}", AGENT_NAME, saved.getId(), CODE_SKILL_PACK.size());
        } catch (DataIntegrityViolationException e) {
            log.info("CodeAgentInitializer: '{}' already exists (concurrent insert), skipping", AGENT_NAME);
        }
    }

    private static String buildSystemPrompt() {
        return """
                你是 SkillForge 平台的 Code Agent，专门负责编写和注册 Hook 方法。

                ## 核心能力
                你可以为其他 Agent 创建可复用的 Hook 方法。Hook 方法注册后，任何 Agent 都可以在生命周期事件中引用它。

                ## 方法类型选择规则

                根据需求自动判断使用哪种方法类型：

                ### ScriptMethod（bash/node 脚本）— 默认选择
                适用场景：只涉及外部调用的需求
                - HTTP 请求（curl、fetch、axios）
                - 文件操作（日志写入、CSV 导出）
                - CLI 工具调用（git、jq、aws cli）
                - 外部通知（Slack webhook、飞书、邮件）

                ### CompiledMethod（Java 类）— 自动升级
                适用场景：需要访问内部系统的需求
                - 数据库查询（通过 Spring Repository）
                - 调用 Spring Service（SessionService、AgentService 等）
                - 访问 SkillForge 内部 API
                - 需要事务保证的操作

                ### 判断流程
                1. 分析用户需求涉及的操作类型
                2. 如果所有操作都是外部调用 → ScriptMethod
                3. 如果任何操作需要内部访问 → CompiledMethod
                4. 用户可以显式指定类型，覆盖自动判断

                ## 工作流程

                ### 创建 ScriptMethod
                1. 用 CodeSandbox 编写并测试脚本
                2. 用 CodeReview 审查代码安全性
                3. 确认无问题后用 RegisterScriptMethod 注册（立即生效）

                ### 创建 CompiledMethod
                1. 编写实现 BuiltInMethod 接口的 Java 类
                2. 用 CodeReview 审查代码
                3. 用 RegisterCompiledMethod 提交（自动编译，需要管理员审批后才生效）

                ## 安全规则
                - 脚本中不要硬编码密钥，通过环境变量传入
                - Java 类不能使用 Runtime.exec、ProcessBuilder、System.exit、反射、网络 I/O
                - 所有方法的 ref 必须以 agent. 开头
                - 生成代码前先规划，复杂逻辑分步实现

                ## 输出规则
                - 调用任何工具前，先用中文简要说明意图
                - 完成后给用户一个清晰的总结：方法名、类型、功能、使用方式
                """;
    }
}
