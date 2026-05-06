package com.skillforge.server.improve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.EvalTaskEntity;
import com.skillforge.server.entity.PromptAbRunEntity;
import com.skillforge.server.entity.PromptVersionEntity;
import com.skillforge.server.eval.attribution.FailureAttribution;
import com.skillforge.server.repository.AgentRepository;
import com.skillforge.server.repository.EvalTaskRepository;
import com.skillforge.server.repository.PromptAbRunRepository;
import com.skillforge.server.repository.PromptVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
public class PromptImproverService {

    private static final Logger log = LoggerFactory.getLogger(PromptImproverService.class);

    private static final Set<FailureAttribution> ELIGIBLE = Set.of(
            FailureAttribution.PROMPT_QUALITY, FailureAttribution.CONTEXT_OVERFLOW);

    private final AgentRepository agentRepository;
    private final EvalTaskRepository evalRunRepository;
    private final PromptVersionRepository promptVersionRepository;
    private final PromptAbRunRepository promptAbRunRepository;
    private final AbEvalPipeline abEvalPipeline;
    private final PromptPromotionService promotionService;
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;
    private final ExecutorService coordinatorExecutor;
    private final String defaultProviderName;

    public PromptImproverService(AgentRepository agentRepository,
                                  EvalTaskRepository evalRunRepository,
                                  PromptVersionRepository promptVersionRepository,
                                  PromptAbRunRepository promptAbRunRepository,
                                  AbEvalPipeline abEvalPipeline,
                                  PromptPromotionService promotionService,
                                  LlmProviderFactory llmProviderFactory,
                                  ObjectMapper objectMapper,
                                  @Qualifier("abEvalCoordinatorExecutor") ExecutorService coordinatorExecutor,
                                  LlmProperties llmProperties) {
        this.agentRepository = agentRepository;
        this.evalRunRepository = evalRunRepository;
        this.promptVersionRepository = promptVersionRepository;
        this.promptAbRunRepository = promptAbRunRepository;
        this.abEvalPipeline = abEvalPipeline;
        this.promotionService = promotionService;
        this.llmProviderFactory = llmProviderFactory;
        this.objectMapper = objectMapper;
        this.coordinatorExecutor = coordinatorExecutor;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    @Transactional
    public ImprovementStartResult startImprovement(String agentId, String evalRunId, long userId) {
        return startImprovement(agentId, evalRunId, userId, null);
    }

    @Transactional
    public ImprovementStartResult startImprovement(String agentId,
                                                   String evalRunId,
                                                   long userId,
                                                   String improvementSuggestion) {
        AgentEntity agent = agentRepository.findById(Long.parseLong(agentId))
                .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));
        EvalTaskEntity evalRun = evalRunRepository.findById(evalRunId)
                .orElseThrow(() -> new RuntimeException("Eval run not found: " + evalRunId));

        // 1. Check eligibility
        checkEligibility(agent, evalRun);

        // 2. Pre-check for active AB run to provide a clean 409 (DB unique index is the final guard)
        boolean hasActive = !promptAbRunRepository.findByAgentIdAndStatus(agentId, "PENDING").isEmpty()
                || !promptAbRunRepository.findByAgentIdAndStatus(agentId, "RUNNING").isEmpty();
        if (hasActive) {
            throw new ImprovementConflictException("An improvement run is already active for agent " + agentId);
        }

        // 3. Create PromptVersionEntity (content placeholder until LLM generates it)
        int nextVersion = promptVersionRepository.findMaxVersionNumber(agentId).orElse(0) + 1;
        PromptVersionEntity version = new PromptVersionEntity();
        version.setId(UUID.randomUUID().toString());
        version.setAgentId(agentId);
        version.setContent(""); // placeholder, will be filled by LLM
        version.setVersionNumber(nextVersion);
        version.setStatus("candidate");
        version.setSource("auto_improve");
        version.setSourceEvalRunId(evalRunId);
        version.setBaselinePassRate(evalRun.getOverallPassRate());
        if (improvementSuggestion != null && !improvementSuggestion.isBlank()) {
            version.setImprovementRationale(improvementSuggestion.trim());
        }
        promptVersionRepository.save(version);

        // 4. Create PromptAbRunEntity (unique index on active runs prevents races)
        PromptAbRunEntity abRun = new PromptAbRunEntity();
        abRun.setId(UUID.randomUUID().toString());
        abRun.setAgentId(agentId);
        abRun.setPromptVersionId(version.getId());
        abRun.setBaselineEvalRunId(evalRunId);
        abRun.setTriggeredByUserId(userId);
        promptAbRunRepository.save(abRun);

        // 5. Submit async improvement after this transaction commits
        String versionId = version.getId();
        String abRunId = abRun.getId();
        coordinatorExecutor.submit(() -> runImprovementAsync(versionId, abRunId, evalRunId, agentId));

        // 6. Return result
        return new ImprovementStartResult(agentId, abRun.getId(), version.getId(), "PENDING");
    }

    private void checkEligibility(AgentEntity agent, EvalTaskEntity evalRun) {
        if (!"COMPLETED".equals(evalRun.getStatus())) {
            throw new ImprovementIneligibleException("EVAL_NOT_COMPLETED");
        }

        if (evalRun.getPrimaryAttribution() == null || !ELIGIBLE.contains(evalRun.getPrimaryAttribution())) {
            throw new ImprovementIneligibleException("INELIGIBLE_ATTRIBUTION");
        }

        if (agent.getLastPromotedAt() != null
                && agent.getLastPromotedAt().isAfter(Instant.now().minusSeconds(24 * 3600))) {
            throw new ImprovementIneligibleException("COOLDOWN_ACTIVE");
        }

        if (agent.isAutoImprovePaused()) {
            throw new ImprovementIneligibleException("AUTO_IMPROVE_PAUSED");
        }
    }

    private void runImprovementAsync(String versionId, String abRunId, String evalRunId, String agentId) {
        try {
            // Reload fresh entities from DB (we're in a new thread/transaction context)
            PromptVersionEntity version = promptVersionRepository.findById(versionId)
                    .orElseThrow(() -> new RuntimeException("Version not found: " + versionId));
            PromptAbRunEntity abRun = promptAbRunRepository.findById(abRunId)
                    .orElseThrow(() -> new RuntimeException("AB run not found: " + abRunId));
            EvalTaskEntity evalRun = evalRunRepository.findById(evalRunId)
                    .orElseThrow(() -> new RuntimeException("Eval run not found: " + evalRunId));
            AgentEntity agent = agentRepository.findById(Long.parseLong(agentId))
                    .orElseThrow(() -> new RuntimeException("Agent not found: " + agentId));

            // 1. Generate candidate prompt via LLM
            String candidatePrompt = generateCandidatePrompt(agent, evalRun, version.getImprovementRationale());
            version.setContent(candidatePrompt);
            promptVersionRepository.save(version);

            // 2. Run AB eval pipeline
            abEvalPipeline.run(abRun, version, evalRun, agent);

            // 3. Evaluate and potentially promote
            promotionService.evaluateAndPromote(abRunId, agentId);

        } catch (Exception e) {
            log.error("Improvement async run failed for agent {}: {}", agentId, e.getMessage(), e);
            // Reload abRun to avoid overwriting a COMPLETED status that may have been set by the pipeline
            promptAbRunRepository.findById(abRunId).ifPresent(fresh -> {
                if (!"COMPLETED".equals(fresh.getStatus())) {
                    fresh.setStatus("FAILED");
                    fresh.setFailureReason(e.getMessage());
                    fresh.setCompletedAt(Instant.now());
                    promptAbRunRepository.save(fresh);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private String generateCandidatePrompt(AgentEntity agent,
                                           EvalTaskEntity evalRun,
                                           String improvementSuggestion) {
        String currentPrompt = agent.getSystemPrompt() != null ? agent.getSystemPrompt() : "";

        // Build failure analysis from scenarioResultsJson
        StringBuilder failureAnalysis = new StringBuilder();
        StringBuilder passExamples = new StringBuilder();

        if (evalRun.getScenarioResultsJson() != null) {
            try {
                List<Map<String, Object>> results = objectMapper.readValue(
                        evalRun.getScenarioResultsJson(),
                        new TypeReference<List<Map<String, Object>>>() {});

                List<Map<String, Object>> eligibleFailures = results.stream()
                        .filter(r -> {
                            String attr = (String) r.get("attribution");
                            return attr != null && ELIGIBLE.stream()
                                    .anyMatch(e -> e.name().equals(attr));
                        })
                        .filter(r -> !Boolean.TRUE.equals(r.get("pass")))
                        .toList();

                for (Map<String, Object> failure : eligibleFailures) {
                    failureAnalysis.append("- Scenario: ").append(failure.get("name"))
                            .append("\n  Task: ").append(failure.get("task"))
                            .append("\n  Attribution: ").append(failure.get("attribution"))
                            .append("\n  Score: ").append(failure.get("compositeScore"))
                            .append("\n");
                }

                List<Map<String, Object>> passes = results.stream()
                        .filter(r -> Boolean.TRUE.equals(r.get("pass")))
                        .limit(3)
                        .toList();

                for (Map<String, Object> pass : passes) {
                    passExamples.append("- Scenario: ").append(pass.get("name"))
                            .append(" (score: ").append(pass.get("compositeScore")).append(")\n");
                }
            } catch (Exception e) {
                log.warn("Failed to parse scenario results for prompt generation", e);
            }
        }

        String systemPrompt = """
                You are an expert prompt engineer. Your task is to analyze the failure patterns \
                from an AI agent's evaluation run and generate an improved system prompt.

                Rules:
                - Output ONLY the improved system prompt text, nothing else
                - Preserve the core intent and capabilities of the original prompt
                - Focus on addressing the specific failure patterns identified
                - Keep the prompt concise and actionable
                - Do not add meta-commentary or explanations""";

        String suggestionSection = (improvementSuggestion == null || improvementSuggestion.isBlank())
                ? ""
                : "\nAnalysis improvement suggestion:\n" + improvementSuggestion.trim() + "\n";

        String userMessage = String.format("""
                Current system prompt:
                ---
                %s
                ---

                Primary failure attribution: %s
                Overall pass rate: %.1f%%

                Failed scenarios (attributed to prompt quality / context overflow):
                %s

                Passing scenario examples:
                %s

                %s

                Generate an improved system prompt that addresses these failure patterns.""",
                currentPrompt,
                evalRun.getPrimaryAttribution() != null ? evalRun.getPrimaryAttribution().name() : "UNKNOWN",
                evalRun.getOverallPassRate(),
                failureAnalysis.length() > 0 ? failureAnalysis.toString() : "(none)",
                passExamples.length() > 0 ? passExamples.toString() : "(none)",
                suggestionSection);

        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) {
            throw new RuntimeException("No LLM provider available for prompt generation");
        }

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));
        request.setMessages(messages);
        request.setMaxTokens(2000);
        request.setTemperature(0.3);

        LlmResponse response = provider.chat(request);
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM returned empty candidate prompt");
        }
        return content.trim();
    }
}
