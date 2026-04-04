package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 子 Agent 执行器，管理异步子 Agent 任务的分派、查询、确认回复和取消。
 */
public class SubAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentExecutor.class);

    private final ExecutorService executorService;
    private final ConcurrentHashMap<String, SubAgentTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> confirmLatches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<LoopResult>> futures = new ConcurrentHashMap<>();

    private final LlmProviderFactory llmProviderFactory;
    private final String defaultProviderName;
    private final SkillRegistry skillRegistry;

    public SubAgentExecutor(LlmProviderFactory llmProviderFactory,
                            String defaultProviderName,
                            SkillRegistry skillRegistry) {
        this.llmProviderFactory = llmProviderFactory;
        this.defaultProviderName = defaultProviderName;
        this.skillRegistry = skillRegistry;
        this.executorService = Executors.newFixedThreadPool(5);
    }

    /**
     * 分派一个子 Agent 任务，异步执行。
     *
     * @param parentSessionId 父 session ID
     * @param targetAgent     目标 Agent 定义
     * @param task            任务描述
     * @param maxTurns        最大循环次数
     * @return 创建的 SubAgentTask
     */
    public SubAgentTask dispatch(String parentSessionId, AgentDefinition targetAgent,
                                  String task, int maxTurns) {
        String taskId = UUID.randomUUID().toString();
        SubAgentTask subTask = new SubAgentTask(
                taskId, parentSessionId,
                targetAgent.getId() != null ? Long.parseLong(targetAgent.getId()) : null,
                targetAgent.getName(), task, maxTurns);
        tasks.put(taskId, subTask);

        CompletableFuture<LoopResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                subTask.setStatus(TaskStatus.RUNNING);
                subTask.setStartedAt(LocalDateTime.now());

                // 创建子 Agent 专用的 SkillRegistry，包含主 registry 的所有 Skill + AskForConfirmation
                SkillRegistry subRegistry = new SkillRegistry();
                for (Skill skill : skillRegistry.getAllSkills()) {
                    subRegistry.register(skill);
                }

                // 创建 AskForConfirmationSkill 并注册
                AskForConfirmationSkill askSkill = new AskForConfirmationSkill(SubAgentExecutor.this);
                subRegistry.register(askSkill);

                // 创建子 AgentLoopEngine
                AgentLoopEngine engine = new AgentLoopEngine(
                        llmProviderFactory, defaultProviderName, subRegistry,
                        Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

                // 克隆 AgentDefinition 并设置 max_loops
                AgentDefinition subAgentDef = new AgentDefinition();
                subAgentDef.setId(targetAgent.getId());
                subAgentDef.setName(targetAgent.getName());
                subAgentDef.setDescription(targetAgent.getDescription());
                subAgentDef.setModelId(targetAgent.getModelId());
                subAgentDef.setSystemPrompt(targetAgent.getSystemPrompt());
                subAgentDef.setSkillIds(targetAgent.getSkillIds());
                java.util.Map<String, Object> subConfig = new java.util.HashMap<>(targetAgent.getConfig());
                subConfig.put("max_loops", maxTurns);
                subConfig.put("sub_agent_task_id", taskId);
                subAgentDef.setConfig(subConfig);

                // 构建 LoopContext 的 session ID（子任务专用）
                String subSessionId = "sub-" + taskId;

                // 运行 agent loop
                LoopResult loopResult = engine.run(subAgentDef, task, null, subSessionId, null);

                // 收集工具调用摘要
                if (loopResult.getToolCalls() != null) {
                    List<String> summary = loopResult.getToolCalls().stream()
                            .map(tc -> tc.getSkillName() + (tc.isSuccess() ? " [OK]" : " [FAIL]"))
                            .collect(Collectors.toList());
                    subTask.setToolCallSummary(summary);
                }

                subTask.setStatus(TaskStatus.COMPLETED);
                subTask.setResult(loopResult.getFinalResponse());
                subTask.setCompletedAt(LocalDateTime.now());
                log.info("Sub-agent task {} completed successfully", taskId);
                return loopResult;

            } catch (Exception e) {
                log.error("Sub-agent task {} failed", taskId, e);
                subTask.setStatus(TaskStatus.FAILED);
                subTask.setError(e.getMessage());
                subTask.setCompletedAt(LocalDateTime.now());
                throw new RuntimeException(e);
            }
        }, executorService);

        futures.put(taskId, future);
        return subTask;
    }

    /**
     * 查询子任务状态。
     */
    public SubAgentTask queryTask(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 回复子 Agent 的确认请求。
     */
    public void respondToConfirmation(String taskId, String response) {
        SubAgentTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        if (task.getStatus() != TaskStatus.PENDING_CONFIRM) {
            throw new IllegalStateException("Task is not pending confirmation, current status: " + task.getStatus());
        }
        task.setConfirmResponse(response);
        CountDownLatch latch = confirmLatches.get(taskId);
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * 取消子任务。
     */
    public void cancelTask(String taskId) {
        SubAgentTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        CompletableFuture<LoopResult> future = futures.get(taskId);
        if (future != null) {
            future.cancel(true);
        }
        // 如果正在等待确认，也释放 latch
        CountDownLatch latch = confirmLatches.get(taskId);
        if (latch != null) {
            latch.countDown();
        }
        task.setStatus(TaskStatus.CANCELLED);
        task.setCompletedAt(LocalDateTime.now());
        log.info("Sub-agent task {} cancelled", taskId);
    }

    /**
     * 列出某 session 的所有子任务。
     */
    public List<SubAgentTask> listTasks(String parentSessionId) {
        List<SubAgentTask> result = new ArrayList<>();
        for (SubAgentTask task : tasks.values()) {
            if (parentSessionId.equals(task.getParentSessionId())) {
                result.add(task);
            }
        }
        return result;
    }

    /**
     * 设置子任务的确认问题并等待回复（供 AskForConfirmationSkill 调用）。
     *
     * @param taskId   任务 ID
     * @param question 确认问题
     * @return 确认回复内容
     * @throws InterruptedException 等待被中断
     */
    public String waitForConfirmation(String taskId, String question) throws InterruptedException {
        SubAgentTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        task.setConfirmQuestion(question);
        task.setConfirmResponse(null);
        task.setStatus(TaskStatus.PENDING_CONFIRM);

        CountDownLatch latch = new CountDownLatch(1);
        confirmLatches.put(taskId, latch);

        log.info("Sub-agent task {} waiting for confirmation: {}", taskId, question);

        // 等待最多 10 分钟
        boolean responded = latch.await(10, java.util.concurrent.TimeUnit.MINUTES);
        confirmLatches.remove(taskId);

        if (!responded) {
            task.setStatus(TaskStatus.RUNNING);
            return "Confirmation timed out after 10 minutes, no response received.";
        }

        // 检查是否被取消
        if (task.getStatus() == TaskStatus.CANCELLED) {
            throw new InterruptedException("Task was cancelled while waiting for confirmation");
        }

        task.setStatus(TaskStatus.RUNNING);
        String response = task.getConfirmResponse();
        log.info("Sub-agent task {} received confirmation response: {}", taskId, response);
        return response;
    }
}
