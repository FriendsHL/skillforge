package com.skillforge.server.improve;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.entity.SkillEvolutionRunEntity;
import com.skillforge.server.repository.SkillEvolutionRunRepository;
import com.skillforge.server.repository.SkillRepository;
import com.skillforge.server.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

@Service
public class SkillEvolutionService {

    private static final Logger log = LoggerFactory.getLogger(SkillEvolutionService.class);

    private static final String EVOLUTION_SYSTEM_PROMPT = """
            You are an expert at improving AI agent skill definitions.
            A Skill is a reusable instruction that guides an AI agent on how to handle a specific task.
            Your job: analyze the current SKILL.md and usage statistics, then output an improved version.

            Rules:
            - Output ONLY the improved SKILL.md content. No explanation, no markdown fences, no preamble.
            - Preserve the overall structure (# Title, description, ## When to use, ## Instructions, etc.)
            - Make instructions clearer, more specific, and more actionable
            - If success rate is low, focus on clarifying ambiguous instructions or adding missing edge cases
            - Keep it concise — a good SKILL.md is 200-600 words
            """;

    private final SkillRepository skillRepository;
    private final SkillEvolutionRunRepository evolutionRunRepository;
    private final SkillAbEvalService skillAbEvalService;
    private final SkillService skillService;
    private final LlmProviderFactory llmProviderFactory;
    private final ExecutorService evolutionExecutor;
    private final String defaultProviderName;

    public SkillEvolutionService(SkillRepository skillRepository,
                                 SkillEvolutionRunRepository evolutionRunRepository,
                                 SkillAbEvalService skillAbEvalService,
                                 SkillService skillService,
                                 LlmProviderFactory llmProviderFactory,
                                 LlmProperties llmProperties,
                                 @Qualifier("skillEvolutionExecutor") ExecutorService evolutionExecutor) {
        this.skillRepository = skillRepository;
        this.evolutionRunRepository = evolutionRunRepository;
        this.skillAbEvalService = skillAbEvalService;
        this.skillService = skillService;
        this.llmProviderFactory = llmProviderFactory;
        this.evolutionExecutor = evolutionExecutor;
        this.defaultProviderName = llmProperties.getDefaultProvider() != null
                ? llmProperties.getDefaultProvider() : "claude";
    }

    public SkillEvolutionRunEntity createAndTrigger(Long skillId, String agentId, Long triggeredByUserId) {
        SkillEntity skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));
        if ("system".equals(skill.getSource())) {
            throw new RuntimeException("Cannot evolve system skills");
        }
        boolean inProgress = evolutionRunRepository
                .findBySkillIdAndStatusIn(skillId, List.of("PENDING", "RUNNING"))
                .stream().anyMatch(r -> true);
        if (inProgress) {
            throw new RuntimeException("An evolution run is already in progress for this skill");
        }

        SkillEvolutionRunEntity run = new SkillEvolutionRunEntity();
        run.setId(UUID.randomUUID().toString());
        run.setSkillId(skillId);
        run.setAgentId(agentId);
        run.setStatus("PENDING");
        run.setTriggeredByUserId(triggeredByUserId);
        run.setUsageCountBefore(skill.getUsageCount());
        if (skill.getUsageCount() > 0) {
            run.setSuccessRateBefore(
                    (double) skill.getSuccessCount() / skill.getUsageCount() * 100);
        }
        SkillEvolutionRunEntity saved = evolutionRunRepository.save(run);

        try {
            evolutionExecutor.submit(() -> runEvolutionAsync(saved.getId()));
        } catch (RejectedExecutionException e) {
            saved.setStatus("FAILED");
            saved.setFailureReason("Evolution executor rejected task: " + e.getMessage());
            saved.setCompletedAt(Instant.now());
            evolutionRunRepository.save(saved);
            throw new RuntimeException("Failed to schedule skill evolution: executor is full or shutdown", e);
        }
        log.info("Created skill evolution run: id={} skillId={} agentId={}",
                saved.getId(), skillId, agentId);
        return saved;
    }

    private void runEvolutionAsync(String evolutionRunId) {
        SkillEvolutionRunEntity run = evolutionRunRepository.findById(evolutionRunId).orElse(null);
        if (run == null) {
            log.warn("SkillEvolutionRun not found for async execution: {}", evolutionRunId);
            return;
        }
        try {
            run.setStatus("RUNNING");
            run.setStartedAt(Instant.now());
            evolutionRunRepository.save(run);

            SkillEntity skill = skillRepository.findById(run.getSkillId())
                    .orElseThrow(() -> new RuntimeException("Skill vanished: " + run.getSkillId()));

            String currentSkillMd = loadCurrentSkillMd(skill);
            String improvedContent = callLlmForImprovement(skill, currentSkillMd);

            SkillEntity forkedSkill = skillService.forkSkill(skill.getId(), null);

            // forkSkill shares parent's skillPath. If we write improved SKILL.md to that path,
            // we clobber the parent and the A/B eval loses its baseline. Create an isolated
            // directory for the fork, copy parent's package contents, then overwrite SKILL.md.
            if (skill.getSkillPath() != null) {
                Path isolatedPath = prepareForkDirectory(skill, forkedSkill);
                Files.writeString(
                        isolatedPath.resolve("SKILL.md"),
                        improvedContent,
                        StandardCharsets.UTF_8);
                forkedSkill.setSkillPath(isolatedPath.toAbsolutePath().toString());
                forkedSkill = skillRepository.save(forkedSkill);
                log.info("Wrote improved SKILL.md to forked skill dir: {}", isolatedPath);
            } else {
                log.info("Parent skill {} has no skillPath; skipping SKILL.md write (AB eval will use metadata fallback)",
                        skill.getId());
            }

            run.setForkedSkillId(forkedSkill.getId());
            run.setImprovedSkillMd(improvedContent);

            String abRunId = null;
            boolean abFailed = false;
            try {
                abRunId = skillAbEvalService.createAndTrigger(
                        skill.getId(), forkedSkill.getId(), run.getAgentId(), null, run.getTriggeredByUserId()).getId();
            } catch (Exception abErr) {
                log.warn("A/B test trigger failed for evolution run {}: {}", evolutionRunId, abErr.getMessage());
                run.setFailureReason("Fork created but A/B trigger failed: " + abErr.getMessage());
                abFailed = true;
            }
            run.setAbRunId(abRunId);
            run.setStatus(abFailed ? "PARTIAL" : "COMPLETED");
            run.setCompletedAt(Instant.now());
            evolutionRunRepository.save(run);
            log.info("Skill evolution completed: runId={}, forkedSkillId={}, abRunId={}",
                    run.getId(), forkedSkill.getId(), abRunId);
        } catch (Exception e) {
            log.error("Skill evolution failed: runId={}", evolutionRunId, e);
            try {
                run.setStatus("FAILED");
                run.setFailureReason(e.toString());
                run.setCompletedAt(Instant.now());
                evolutionRunRepository.save(run);
            } catch (Exception saveErr) {
                log.error("Failed to persist FAILED status for evolutionRunId={}", evolutionRunId, saveErr);
            }
        }
    }

    private String loadCurrentSkillMd(SkillEntity skill) {
        try {
            return skillService.getSkillPromptContent(skill.getId());
        } catch (RuntimeException e) {
            log.warn("Failed to read SKILL.md for skill {}, falling back to description: {}",
                    skill.getId(), e.getMessage());
            StringBuilder fallback = new StringBuilder();
            fallback.append("# ").append(skill.getName()).append("\n\n");
            if (skill.getDescription() != null) {
                fallback.append(skill.getDescription()).append("\n");
            }
            return fallback.toString();
        }
    }

    private String callLlmForImprovement(SkillEntity skill, String currentSkillMd) {
        LlmProvider provider = llmProviderFactory.getProvider(defaultProviderName);
        if (provider == null) {
            throw new RuntimeException("No LLM provider available");
        }

        double rate = skill.getUsageCount() > 0
                ? (double) skill.getSuccessCount() / skill.getUsageCount() * 100
                : 0.0;
        String userMessage = String.format(
                "Skill name: %s%n" +
                        "Usage count: %d%n" +
                        "Success count: %d%n" +
                        "Success rate: %.2f%%%n%n" +
                        "Current SKILL.md:%n%s",
                skill.getName(),
                skill.getUsageCount(),
                skill.getSuccessCount(),
                rate,
                currentSkillMd);

        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(EVOLUTION_SYSTEM_PROMPT);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));
        request.setMessages(messages);
        request.setMaxTokens(3000);
        request.setTemperature(0.3);

        LlmResponse response = provider.chat(request);
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            throw new RuntimeException("LLM returned empty response");
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("```(?:markdown|md)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private Path prepareForkDirectory(SkillEntity parent, SkillEntity forkedSkill) throws IOException {
        Path parentDir = Path.of(parent.getSkillPath()).toAbsolutePath().normalize();
        Path anchor = parentDir.getParent() != null ? parentDir.getParent() : parentDir;
        Path newDir = anchor.resolve("evolved-" + forkedSkill.getId());
        Files.createDirectories(newDir);
        if (Files.isDirectory(parentDir)) {
            copyDirectory(parentDir, newDir);
        }
        return newDir;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            List<Path> paths = stream.sorted(Comparator.naturalOrder()).toList();
            for (Path src : paths) {
                Path rel = source.relativize(src);
                Path dst = target.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    if (dst.getParent() != null) {
                        Files.createDirectories(dst.getParent());
                    }
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public List<SkillEvolutionRunEntity> getEvolutionRuns(Long skillId) {
        return evolutionRunRepository.findBySkillIdOrderByCreatedAtDesc(skillId);
    }

    public Optional<SkillEvolutionRunEntity> getEvolutionRun(String id) {
        return evolutionRunRepository.findById(id);
    }
}
