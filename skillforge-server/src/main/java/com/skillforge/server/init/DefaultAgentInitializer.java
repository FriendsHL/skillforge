package com.skillforge.server.init;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时若 t_agent 表为空,自动插入一个开箱即用的 Main Assistant Agent。
 * 幂等:仅当 count()==0 时执行。
 */
@Component
public class DefaultAgentInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentInitializer.class);

    private final AgentRepository agentRepository;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultAgentInitializer(AgentRepository agentRepository, SkillRegistry skillRegistry) {
        this.agentRepository = agentRepository;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        long existing = agentRepository.count();
        if (existing > 0) {
            log.info("DefaultAgentInitializer: skip, already have {} agent(s)", existing);
            return;
        }

        List<String> skillNames = skillRegistry.getAllSkills().stream()
                .map(Skill::getName)
                // 默认 Main Assistant 不直接绑 subAgent (没有可委派的子 agent)
                .filter(n -> !"subAgent".equalsIgnoreCase(n))
                .sorted()
                .toList();

        AgentEntity agent = new AgentEntity();
        agent.setName("Main Assistant");
        agent.setDescription("默认通用助手 Agent,开箱即用,绑定全部内置工具。");
        agent.setModelId("qwen3.5-plus");
        agent.setSystemPrompt(buildSystemPrompt());
        try {
            agent.setSkillIds(objectMapper.writeValueAsString(skillNames));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize default skill list, fallback to empty", e);
            agent.setSkillIds("[]");
        }
        agent.setConfig("{\"temperature\":0.7,\"maxTokens\":4096}");
        agent.setOwnerId(null);
        agent.setPublic(true);
        agent.setStatus("active");
        agent.setExecutionMode("ask");

        AgentEntity saved = agentRepository.save(agent);
        log.info("DefaultAgentInitializer: created Main Assistant id={}, skills={}",
                saved.getId(), skillNames);
    }

    private String buildSystemPrompt() {
        return "你是 SkillForge 平台的默认通用助手 (Main Assistant),擅长理解需求、规划步骤、调用工具完成任务。\n" +
                "\n" +
                "【最重要的规则 / 必读】\n" +
                "在调用任何工具之前,你必须先用一两句中文向用户说明:你打算做什么、为什么要这么做。\n" +
                "前端 UI 依赖你输出的 text 来向用户显示\"思考过程\",如果你直接调用工具不开口,用户会以为程序卡住了。\n" +
                "\n" +
                "正确示范:\n" +
                "  我需要先看一下当前目录有哪些文件,我用 bash 跑个 ls。\n" +
                "  [tool_use: bash]\n" +
                "\n" +
                "错误示范:\n" +
                "  [tool_use: bash]   ← 一句话不说就动手,禁止!\n" +
                "\n" +
                "工作准则:\n" +
                "1. 先想清楚再动手。复杂任务先用 1-2 句话拆解步骤,再开始执行。\n" +
                "2. 调用工具前先开口,简短说明意图(见上)。\n" +
                "3. 工具结果回来后,简要复述关键信息再决定下一步。\n" +
                "4. 文件路径优先用绝对路径。修改文件前先 read。\n" +
                "5. 完成任务后给用户一个清晰的总结。\n" +
                "\n" +
                "【最后再强调一遍】不要不说话直接调工具。先说,再做。";
    }
}
