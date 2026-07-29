package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.RequestTokenEstimator;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.capability.ToolCatalog;
import com.skillforge.core.capability.ToolDescriptor;
import com.skillforge.core.capability.ToolDiscoveryState;
import com.skillforge.core.capability.ToolSearchCapability;
import com.skillforge.core.context.ContextAttachment;
import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.context.DynamicSystemPromptAppender;
import com.skillforge.core.context.LegacyCompatiblePromptRenderer;
import com.skillforge.core.context.PromptAssembly;
import com.skillforge.core.context.PromptObservationHashes;
import com.skillforge.core.context.SystemPromptBuilder;
import com.skillforge.core.engine.AgentLoopEngine;
import com.skillforge.core.llm.cache.ToolNormalizer;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.reminder.ReminderBuilder;
import com.skillforge.core.reminder.ReminderObservation;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.dto.ContextBreakdownDto.Segment;
import com.skillforge.server.dto.ContextBreakdownDto.SegmentMetadata;
import com.skillforge.server.dto.ContextBreakdownDto.ObservationSummary;
import com.skillforge.server.config.ContextObservationProperties;
import com.skillforge.server.config.ContextCapabilityProperties;
import com.skillforge.server.entity.AgentEntity;
import com.skillforge.server.entity.SessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Estimates how the current context window for a session is being spent — system prompt
 * segments, tool schemas JSON, and conversation messages (with a memory sub-bucket).
 *
 * <p>Uses {@link TokenEstimator} (±10%) so callers get a <em>proportional</em> view, not a
 * billing-grade number. Intended to back the chat right-rail "Context" panel.
 */
@Service
public class ContextBreakdownService {

    private static final Logger log = LoggerFactory.getLogger(ContextBreakdownService.class);

    /** Skill/tool names whose tool_results we attribute to the "memory" bucket. */
    private static final Set<String> MEMORY_SKILL_NAMES = Set.of(
            "memory_search", "memory_detail", "Memory");

    private static final String DEFAULT_TOOLS_GUIDELINES =
            "## Tool Usage Guidelines\n\n"
                    + "- Use Read instead of running `cat` or `head` via Bash\n"
                    + "- Use Glob instead of running `find` or `ls` via Bash\n"
                    + "- Use Grep instead of running `grep` or `rg` via Bash\n"
                    + "- Use Edit for modifying existing files instead of Write\n"
                    + "- Always read a file before editing or overwriting it\n"
                    + "- Use absolute file paths whenever possible\n";

    private final AgentService agentService;
    private final SessionService sessionService;
    private final SkillRegistry skillRegistry;
    private final MemoryService memoryService;
    private final com.skillforge.core.context.GlobalSystemPromptProvider globalSystemPromptProvider;
    private final List<ContextProvider> contextProviders;
    private final ObjectMapper objectMapper;
    /** Plan r2 §5 — view 接管 skill 列表渲染（修复 B-BE-3 残留 getAllSkillDefinitions）。 */
    private final SessionSkillResolver sessionSkillResolver;
    private final ContextObservationProperties observationProperties;
    private final ReminderBuilder reminderBuilder;
    private final ContextCapabilityProperties capabilityProperties;

    public ContextBreakdownService(AgentService agentService,
                                   SessionService sessionService,
                                   SkillRegistry skillRegistry,
                                   MemoryService memoryService,
                                   com.skillforge.core.context.GlobalSystemPromptProvider globalSystemPromptProvider,
                                   List<ContextProvider> contextProviders,
                                   ObjectMapper objectMapper,
                                   SessionSkillResolver sessionSkillResolver,
                                   ContextObservationProperties observationProperties,
                                   ReminderBuilder reminderBuilder,
                                   ContextCapabilityProperties capabilityProperties) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.skillRegistry = skillRegistry;
        this.memoryService = memoryService;
        this.globalSystemPromptProvider = globalSystemPromptProvider;
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.objectMapper = objectMapper;
        this.sessionSkillResolver = sessionSkillResolver;
        this.observationProperties = observationProperties;
        this.reminderBuilder = reminderBuilder;
        this.capabilityProperties = capabilityProperties;
    }

    @Transactional(readOnly = true)
    public ContextBreakdownDto breakdown(SessionEntity session, Long userId) {
        long observationStartNanos = System.nanoTime();
        AgentEntity agentEntity = agentService.getAgent(session.getAgentId());
        AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
        String sessionId = session.getId();

        SystemPromptSegments systemPrompt =
                buildSystemPromptSegments(agentDef, userId, session);
        List<Segment> systemPromptChildren = systemPrompt.segments();
        long systemPromptTotal = sumTokens(systemPromptChildren);

        ToolSchemasBreakdown toolSchemasBreakdown = buildToolSchemasSegment(agentDef);
        Segment toolSchemas = toolSchemasBreakdown.segment();

        List<Message> context = sessionService.getContextMessages(sessionId);
        List<Segment> messageChildren = bucketMessages(context);
        long messagesTotal = sumTokens(messageChildren);

        // CTX-1 — include max_tokens output reservation as its own segment so the
        // dashboard total matches AgentLoopEngine's RequestTokenEstimator.estimate()
        // exactly (PRD AC-1 / tech-design D2). Otherwise the engine's compact-trigger
        // ratio would always be larger than the right-rail "context window pct" by
        // exactly maxTokens — confusing for users debugging "why did it compact early?".
        long outputReserved = Math.max(0, agentDef.getMaxTokens());
        Segment outputReservedSeg = Segment.leaf(
                "output_reserved", "Output reservation (max_tokens)", outputReserved);

        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(
                "system_prompt", "System prompt", systemPromptTotal, systemPromptChildren));
        segments.add(toolSchemas);
        segments.add(new Segment("messages", "Messages", messagesTotal, messageChildren));
        Segment reminderObservations = buildReminderObservations(sessionId);
        if (reminderObservations != null) {
            segments.add(reminderObservations);
        }
        segments.add(outputReservedSeg);

        long total = sumTokens(segments);
        long windowLimit = resolveWindowLimit(agentDef, agentEntity.getModelId());
        int pct = windowLimit > 0
                ? (int) Math.min(100L, Math.round(total * 100.0 / windowLimit))
                : 0;

        ObservationSummary observation = null;
        if (observationProperties != null && observationProperties.isEnabled()) {
            observation = new ObservationSummary(
                    systemPrompt.stableHash(),
                    systemPrompt.assemblyHash(),
                    toolSchemasBreakdown.hash(),
                    Math.max(0L, (System.nanoTime() - observationStartNanos) / 1_000L));
        }
        return new ContextBreakdownDto(
                sessionId, total, windowLimit, pct, segments, observation);
    }

    private Segment buildReminderObservations(String sessionId) {
        if (reminderBuilder == null || !reminderBuilder.isStructuredEnabled()) return null;
        List<ReminderObservation> observations = reminderBuilder.getLastObservations(sessionId);
        if (observations.isEmpty()) return null;
        List<Segment> children = observations.stream()
                .map(observation -> Segment.observed(
                        "reminder:" + observation.id(),
                        "Reminder · " + observation.source().name(),
                        0,
                        new SegmentMetadata(
                                "REMINDER",
                                observation.placement().name(),
                                false,
                                false,
                                observation.contentHash(),
                                "REMINDER",
                                observation.source().name(),
                                observation.reasonCode().name(),
                                "PLATFORM",
                                "TRUSTED_RUNTIME_DATA",
                                observation.lifecycle().name(),
                                observation.compactPolicy().name())))
                .toList();
        return new Segment(
                "reminder_observations",
                "Reminder diagnostics (already counted in messages)",
                0,
                children);
    }

    // ─────────────────────────── system prompt segments ───────────────────────────

    private SystemPromptSegments buildSystemPromptSegments(
            AgentDefinition agentDef, Long userId, SessionEntity session) {
        if (observationProperties != null && observationProperties.isEnabled()) {
            return buildObservedSystemPromptSegments(agentDef, userId, session);
        }
        return buildLegacySystemPromptSegments(agentDef, userId, session);
    }

    private SystemPromptSegments buildObservedSystemPromptSegments(
            AgentDefinition agentDef, Long userId, SessionEntity session) {
        List<Segment> out = new ArrayList<>();
        String globalPrompt = safeProviderCall(globalSystemPromptProvider::get);
        PromptAssembly assembly =
                new SystemPromptBuilder(agentDef, List.of(), contextProviders)
                        .buildAssembly(globalPrompt);
        LegacyCompatiblePromptRenderer renderer = new LegacyCompatiblePromptRenderer();
        com.skillforge.core.llm.cache.SystemPromptParts parts = assembly.render(renderer);
        for (ContextAttachment attachment : assembly.attachments()) {
            out.add(Segment.observed(
                    attachment.id(),
                    promptFragmentLabel(attachment.id()),
                    attachment.estimatedTokens(),
                    attachmentMetadata(attachment)));
        }

        StringBuilder dynamic = new StringBuilder(parts.dynamic());
        String sessionCtx = DynamicSystemPromptAppender.appendSessionContext(
                dynamic, userId, session.getId());
        if (!sessionCtx.isEmpty()) {
            ContextAttachment sessionAttachment =
                    ContextAttachment.sessionContext(sessionCtx, userId, session.getId());
            assembly = assembly.withAttachment(sessionAttachment);
            out.add(Segment.observed(
                    "session_context", "Session context",
                    sessionAttachment.estimatedTokens(),
                    attachmentMetadata(sessionAttachment)));
        }

        Map<String, Object> cfg = agentDef.getConfig();
        boolean skipMemory = cfg != null && Boolean.TRUE.equals(cfg.get("skip_memory"));
        if (!skipMemory) {
            String memories = safeProviderCall(
                    () -> memoryService.previewMemoriesForPrompt(userId, null));
            if (isNotBlank(memories)) {
                String memoryFragment =
                        DynamicSystemPromptAppender.appendUserMemories(dynamic, memories);
                ContextAttachment memoryAttachment =
                        ContextAttachment.userMemories(memoryFragment, List.of());
                assembly = assembly.withAttachment(memoryAttachment);
                out.add(Segment.observed(
                        "user_memories", "User memories",
                        memoryAttachment.estimatedTokens(),
                        attachmentMetadata(memoryAttachment)));
            }
        }
        parts = assembly.render(new LegacyCompatiblePromptRenderer(true));
        String renderedAssembly;
        if (parts.dynamic().isEmpty()) {
            renderedAssembly = parts.stable();
        } else if (parts.stable().isEmpty()) {
            renderedAssembly = parts.dynamic();
        } else {
            renderedAssembly = parts.stable()
                    + com.skillforge.core.llm.cache.CacheBoundary.MARKER_WITH_NEWLINES
                    + parts.dynamic();
        }
        long assemblyTokens = TokenEstimator.estimateString(renderedAssembly);
        long framingTokens = assemblyTokens - sumTokens(out);
        if (framingTokens > 0) {
            out.add(Segment.observed(
                    "prompt_framing",
                    "Prompt framing / cache boundary",
                    framingTokens,
                    new SegmentMetadata(
                            "ASSEMBLY",
                            "SYSTEM_BOUNDARY",
                            null,
                            null,
                            PromptObservationHashes.sha256(
                                    com.skillforge.core.llm.cache.CacheBoundary.MARKER_WITH_NEWLINES),
                            null,
                            null,
                            null)));
        }
        return new SystemPromptSegments(
                out,
                PromptObservationHashes.sha256(parts.stable()),
                PromptObservationHashes.sha256(renderedAssembly));
    }

    private SystemPromptSegments buildLegacySystemPromptSegments(
            AgentDefinition agentDef, Long userId, SessionEntity session) {
        List<Segment> out = new ArrayList<>();

        // SKILLFORGE-SYSTEM-PROMPT: the engine now injects the built-in global system prompt
        // as the first stable segment (replacing per-user claudeMd), so the breakdown reports
        // that same global prompt to stay accurate against what's actually sent to the LLM.
        String globalPrompt = safeProviderCall(globalSystemPromptProvider::get);
        if (isNotBlank(globalPrompt)) {
            out.add(Segment.leaf("global_system_prompt", "Global system prompt",
                    TokenEstimator.estimateString(globalPrompt)));
        }

        if (isNotBlank(agentDef.getSystemPrompt())) {
            out.add(Segment.leaf("agent_prompt", "Agent prompt",
                    TokenEstimator.estimateString(agentDef.getSystemPrompt())));
        }

        if (isNotBlank(agentDef.getSoulPrompt())) {
            out.add(Segment.leaf("soul", "SOUL.md",
                    TokenEstimator.estimateString(agentDef.getSoulPrompt())));
        }

        String toolsText = isNotBlank(agentDef.getToolsPrompt())
                ? agentDef.getToolsPrompt() : DEFAULT_TOOLS_GUIDELINES;
        out.add(Segment.leaf("tools_md", "TOOLS.md / Guidelines",
                TokenEstimator.estimateString(toolsText)));

        String behaviorBlock = renderBehaviorRulesBlock(agentDef);
        if (!behaviorBlock.isEmpty()) {
            out.add(Segment.leaf("behavior_rules", "Behavior rules",
                    TokenEstimator.estimateString(behaviorBlock)));
        }

        String contextBlock = renderContextProvidersBlock();
        if (!contextBlock.isEmpty()) {
            out.add(Segment.leaf("env_context", "Environment context",
                    TokenEstimator.estimateString(contextBlock)));
        }

        // Session Context injection (userId / sessionId) done after SystemPromptBuilder —
        // see AgentLoopEngine §4.0.1. Small but real.
        String sessionCtx = renderSessionContextBlock(userId, session.getId());
        if (!sessionCtx.isEmpty()) {
            out.add(Segment.leaf("session_context", "Session context",
                    TokenEstimator.estimateString(sessionCtx)));
        }

        Map<String, Object> cfg = agentDef.getConfig();
        boolean skipMemory = cfg != null && Boolean.TRUE.equals(cfg.get("skip_memory"));
        if (!skipMemory) {
            // previewMemoriesForPrompt renders the same block but skips the recall-count UPDATE
            // that getMemoriesForPromptInjection does — the estimation request is read-only and
            // must not pollute ranking signals.
            String memories = safeProviderCall(() -> memoryService.previewMemoriesForPrompt(userId, null));
            if (isNotBlank(memories)) {
                out.add(Segment.leaf("user_memories", "User memories",
                        TokenEstimator.estimateString(memories)));
            }
        }

        return new SystemPromptSegments(out, null, null);
    }

    private static SegmentMetadata attachmentMetadata(ContextAttachment attachment) {
        return new SegmentMetadata(
                attachment.sourceType().name(),
                attachment.placement().name(),
                attachment.stable(),
                attachment.cacheable(),
                attachment.contentHash(),
                null,
                attachment.sourceIds().isEmpty()
                        ? null : String.join(",", attachment.sourceIds()),
                null,
                attachment.authority().name(),
                attachment.trustLevel().name(),
                attachment.lifecycle().name(),
                attachment.compactPolicy().name());
    }

    private static String promptFragmentLabel(String id) {
        if ("global_system_prompt".equals(id)) return "Global system prompt";
        if ("agent_prompt".equals(id)) return "Agent prompt";
        if ("soul".equals(id)) return "SOUL.md";
        if ("tools_md".equals(id)) return "TOOLS.md / Guidelines";
        if ("behavior_rules".equals(id)) return "Behavior rules";
        if (id != null && id.startsWith("env_context.")) {
            return "Environment context · " + id.substring("env_context.".length());
        }
        return id == null ? "Prompt fragment" : id;
    }

    /**
     * Skills are no longer rendered in the system prompt; they are exposed through
     * the single Skill loader tool schema.
     * <p>Package-private for {@code ContextBreakdownServiceTest}.
     */
    String renderSkillsListBlock(AgentDefinition agentDef) {
        return "";
    }

    private String renderBehaviorRulesBlock(AgentDefinition agentDef) {
        List<String> resolved = agentDef.getResolvedBehaviorRules();
        AgentDefinition.BehaviorRulesConfig cfg = agentDef.getBehaviorRules();
        List<AgentDefinition.BehaviorRulesConfig.CustomRule> custom =
                cfg != null ? cfg.getCustomRules() : null;

        boolean hasBuiltin = resolved != null && !resolved.isEmpty();
        boolean hasCustom = custom != null && !custom.isEmpty();
        if (!hasBuiltin && !hasCustom) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("## Behavior Rules\n\n");
        if (hasBuiltin) {
            sb.append("You MUST follow these behavioral guidelines:\n\n");
            for (int i = 0; i < resolved.size(); i++) {
                sb.append(i + 1).append(". ").append(resolved.get(i)).append("\n");
            }
            sb.append("\n");
        }
        if (hasCustom) {
            sb.append("<user-configured-guidelines>\n");
            appendCustomRuleGroup(sb, "MUST", custom,
                    AgentDefinition.BehaviorRulesConfig.Severity.MUST);
            appendCustomRuleGroup(sb, "SHOULD", custom,
                    AgentDefinition.BehaviorRulesConfig.Severity.SHOULD);
            appendCustomRuleGroup(sb, "MAY", custom,
                    AgentDefinition.BehaviorRulesConfig.Severity.MAY);
            sb.append("</user-configured-guidelines>\n");
        }
        return sb.toString();
    }

    private void appendCustomRuleGroup(
            StringBuilder sb,
            String label,
            List<AgentDefinition.BehaviorRulesConfig.CustomRule> custom,
            AgentDefinition.BehaviorRulesConfig.Severity severity) {
        boolean wroteHeader = false;
        for (AgentDefinition.BehaviorRulesConfig.CustomRule r : custom) {
            if (r == null || r.getSeverity() != severity || !isNotBlank(r.getText())) {
                continue;
            }
            if (!wroteHeader) {
                sb.append(label).append(":\n");
                wroteHeader = true;
            }
            sb.append("- ").append(r.getText()).append("\n");
        }
    }

    private String renderContextProvidersBlock() {
        if (contextProviders.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        sb.append("## Context\n\n");
        for (ContextProvider p : contextProviders) {
            Map<String, String> ctx;
            try {
                ctx = p.getContext();
            } catch (RuntimeException ex) {
                log.debug("ContextProvider {} threw; skipping", p.getName(), ex);
                continue;
            }
            if (ctx == null || ctx.isEmpty()) continue;
            any = true;
            sb.append("### ").append(p.getName()).append("\n");
            for (Map.Entry<String, String> e : ctx.entrySet()) {
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
            sb.append("\n");
        }
        return any ? sb.toString() : "";
    }

    private String renderSessionContextBlock(Long userId, String sessionId) {
        if (userId == null && sessionId == null) return "";
        StringBuilder sb = new StringBuilder("\n\n## Session Context\n");
        if (userId != null) sb.append("- userId: ").append(userId).append("\n");
        if (sessionId != null) sb.append("- sessionId: ").append(sessionId).append("\n");
        return sb.toString();
    }

    // ───────────────────────────── tool schemas ─────────────────────────────

    private ToolSchemasBreakdown buildToolSchemasSegment(AgentDefinition agentDef) {
        Collection<Tool> skills = skillRegistry.getAllTools();
        // CTX-1 — collect ToolSchemas first then delegate to RequestTokenEstimator so
        // the dashboard count and the engine's compact-trigger ratio share one
        // algorithm (FR-1.1 / AC-1). Schema fetch errors continue to be skipped silently
        // (preserve historical behaviour).
        List<ToolSchema> schemas = new ArrayList<>(skills != null ? skills.size() + 1 : 1);
        if (skills != null) {
            for (Tool s : skills) {
                ToolSchema schema;
                try {
                    schema = s.getToolSchema();
                } catch (RuntimeException ex) {
                    log.debug("getToolSchema failed for {}; skipping", s.getName(), ex);
                    continue;
                }
                if (schema != null) schemas.add(schema);
            }
        }

        List<SkillDefinition> visibleSkills = resolveVisibleSkillDefs(agentDef);
        if (!visibleSkills.isEmpty()) {
            schemas.add(AgentLoopEngine.skillLoaderToolSchema(visibleSkills));
        }
        ToolCatalog catalog = null;
        if (capabilityProperties != null && capabilityProperties.isCatalogEnabled()) {
            if (capabilityProperties.isToolSearchEnabled()) {
                schemas.add(ToolSearchCapability.schema());
            }
            catalog = ToolCatalog.fromAuthorizedSchemas(schemas, objectMapper);
            schemas = catalog.exposedSchemas(
                    new ToolDiscoveryState(),
                    capabilityProperties.isDeferredSchemasEnabled());
        }
        long total = RequestTokenEstimator.estimateToolSchemas(schemas, objectMapper);
        if (observationProperties == null || !observationProperties.isEnabled()) {
            return new ToolSchemasBreakdown(
                    Segment.leaf("tool_schemas", "Tool schemas (JSON)", total), null);
        }
        List<Segment> children = new ArrayList<>(schemas.size());
        for (ToolSchema schema : schemas) {
            if (schema == null) continue;
            int tokens = RequestTokenEstimator.estimateToolSchemas(List.of(schema), objectMapper);
            ToolDescriptor descriptor = catalog != null ? catalog.findByName(schema.getName()) : null;
            String kind = descriptor != null ? descriptor.kind().name() : capabilityKind(schema);
            String source = descriptor != null
                    ? descriptor.source().name() : capabilitySource(schema, kind);
            String exposureReason = descriptor != null
                    ? (descriptor.alwaysLoaded()
                        ? "ALWAYS_LOADED"
                        : "VISIBLE_BECAUSE_DEFERRED_ENFORCEMENT_DISABLED")
                    : "VISIBLE_BY_CURRENT_AGENT_POLICY";
            children.add(Segment.observed(
                    "tool_schema_" + sanitizeKey(schema.getName()),
                    nullSafe(schema.getName()),
                    tokens,
                    new SegmentMetadata(
                            null,
                            null,
                            null,
                            null,
                            ToolNormalizer.hashTool(schema, objectMapper),
                            kind,
                            source,
                            exposureReason)));
        }
        children.sort(java.util.Comparator.comparingLong(Segment::tokens).reversed());
        return new ToolSchemasBreakdown(
                new Segment("tool_schemas", "Tool schemas (JSON)", total, children),
                ToolNormalizer.hashTools(schemas, objectMapper));
    }

    private static String capabilityKind(ToolSchema schema) {
        String name = nullSafe(schema.getName());
        String lower = name.toLowerCase(Locale.ROOT);
        if ("Skill".equals(name)) return "SKILL_LOADER";
        if (lower.contains("image") || lower.contains("video") || lower.contains("audio")) {
            return "MEDIA";
        }
        if (lower.startsWith("mcp_")) return "MCP";
        return "TOOL";
    }

    private static String capabilitySource(ToolSchema schema, String kind) {
        if ("SKILL_LOADER".equals(kind)) return "SessionSkillView";
        if ("MCP".equals(kind)) return "MCP";
        return "SkillRegistry";
    }

    private List<SkillDefinition> resolveVisibleSkillDefs(AgentDefinition agentDef) {
        try {
            SessionSkillView view = sessionSkillResolver != null
                    ? sessionSkillResolver.resolveFor(agentDef)
                    : SessionSkillView.EMPTY;
            Collection<SkillDefinition> defs = view != null ? view.all() : List.of();
            return defs != null ? new ArrayList<>(defs) : List.of();
        } catch (Exception e) {
            log.warn("resolveVisibleSkillDefs: resolver failed, treating as empty: {}", e.getMessage());
            return List.of();
        }
    }

    // ───────────────────────────── messages ─────────────────────────────

    private List<Segment> bucketMessages(List<Message> messages) {
        long textTokens = 0L;
        long toolUseTokens = 0L;
        long memoryResultTokens = 0L;
        // Per-tool-name token buckets for non-memory tool results.
        Map<String, Long> otherResultByTool = new LinkedHashMap<>();

        // First pass: map tool_use_id → tool name so we can classify tool_results.
        Map<String, String> toolUseIdToName = new HashMap<>();
        for (Message m : messages) {
            Object content = m.getContent();
            if (content instanceof List<?> blocks) {
                for (Object raw : blocks) {
                    BlockView v = asBlockView(raw);
                    if (v != null && "tool_use".equals(v.type) && v.id != null) {
                        toolUseIdToName.put(v.id, nullSafe(v.name));
                    }
                }
            }
        }

        for (Message m : messages) {
            Object content = m.getContent();
            if (content instanceof String s) {
                textTokens += TokenEstimator.estimateString(s) + /* role overhead */ 4;
                continue;
            }
            if (!(content instanceof List<?> blocks)) continue;
            // per-message overhead (role + start/stop markers)
            textTokens += 4;
            for (Object raw : blocks) {
                BlockView v = asBlockView(raw);
                if (v == null || v.type == null) continue;
                switch (v.type) {
                    case "text" -> textTokens += TokenEstimator.estimateString(v.text);
                    case "tool_use" -> {
                        long t = TokenEstimator.estimateString(nullSafe(v.name));
                        if (v.input != null) {
                            t += TokenEstimator.estimateString(v.input);
                        }
                        toolUseTokens += t;
                    }
                    case "tool_result" -> {
                        long t = TokenEstimator.estimateString(v.contentText);
                        String srcName = toolUseIdToName.get(v.toolUseId);
                        if (isMemoryOrigin(srcName)) {
                            memoryResultTokens += t;
                        } else {
                            String toolName = srcName != null ? srcName : "unknown";
                            otherResultByTool.merge(toolName, t, Long::sum);
                        }
                    }
                    default -> { /* ignore unknown block types */ }
                }
            }
        }

        List<Segment> out = new ArrayList<>();
        out.add(Segment.leaf("conversation_text", "Conversation text", textTokens));
        out.add(Segment.leaf("tool_use", "Tool calls", toolUseTokens));
        out.add(Segment.leaf("tool_result_memory", "Memory results", memoryResultTokens));
        out.add(buildOtherToolResultsSegment(otherResultByTool));
        return out;
    }

    /**
     * Build the {@code tool_result_other} parent segment with per-tool children
     * sorted by tokens descending (largest first — matches the debug intent of
     * "which tool is eating my context"). The parent's {@code tokens} is the sum
     * of all children, so the top-level total stays accurate even when children
     * are collapsed in the UI. Keeps the legacy {@code tool_result_other} key so
     * any FE state keyed on it (palettes, expanded-set persistence) still works.
     */
    private static Segment buildOtherToolResultsSegment(Map<String, Long> otherResultByTool) {
        long total = 0L;
        for (long v : otherResultByTool.values()) total += v;
        if (otherResultByTool.isEmpty()) {
            return Segment.leaf("tool_result_other", "Other tool results", 0L);
        }
        List<Segment> children = otherResultByTool.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> Segment.leaf(
                        "tool_result_" + sanitizeKey(e.getKey()),
                        e.getKey() + " results",
                        e.getValue()))
                .toList();
        return new Segment("tool_result_other", "Other tool results", total, children);
    }

    /** Normalize tool name into a key-safe slug so future MCP/LSP/user-defined
     *  tool names with whitespace or special chars don't break React keys /
     *  aria-controls IDs ({@code ${idPrefix}-${s.key}}). */
    private static String sanitizeKey(String toolName) {
        if (toolName == null || toolName.isBlank()) return "unknown";
        return toolName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /**
     * Uniform view over a content block. Hibernate round-trips {@link Message#getContent()}
     * through Jackson so what we see is typically a {@code LinkedHashMap}, not the typed
     * {@link ContentBlock}; handle both.
     */
    private static final class BlockView {
        final String type;
        final String text;
        final String name;
        final String id;
        final String input;
        final String contentText;
        final String toolUseId;

        BlockView(String type, String text, String name, String id,
                  String input, String contentText, String toolUseId) {
            this.type = type;
            this.text = text;
            this.name = name;
            this.id = id;
            this.input = input;
            this.contentText = contentText;
            this.toolUseId = toolUseId;
        }
    }

    private BlockView asBlockView(Object raw) {
        if (raw instanceof ContentBlock b) {
            return new BlockView(
                    b.getType(),
                    b.getText(),
                    b.getName(),
                    b.getId(),
                    serializeInput(b.getInput()),
                    b.getContent(),
                    b.getToolUseId());
        }
        if (raw instanceof Map<?, ?> m) {
            Object type = m.get("type");
            Object text = m.get("text");
            Object name = m.get("name");
            Object id = m.get("id");
            Object input = m.get("input");
            Object content = m.get("content");
            Object toolUseId = m.get("tool_use_id");
            return new BlockView(
                    type == null ? null : type.toString(),
                    text == null ? null : text.toString(),
                    name == null ? null : name.toString(),
                    id == null ? null : id.toString(),
                    serializeInput(input),
                    flattenContent(content),
                    toolUseId == null ? null : toolUseId.toString());
        }
        return null;
    }

    /**
     * Serialise a tool_use {@code input} payload to JSON for token estimation — the same shape
     * the LLM sees. Falls back to {@code toString()} only if JSON serialisation fails.
     */
    private String serializeInput(Object input) {
        if (input == null) return null;
        if (input instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException ex) {
            return input.toString();
        }
    }

    /**
     * A tool_result {@code content} may be a String OR a list of sub-blocks (Anthropic wire
     * format). Flatten nested structures to JSON so the token estimate reflects what the LLM
     * actually sees — {@code List.toString()} would produce {@code "[{type=text, ...}]"}.
     */
    private String flattenContent(Object content) {
        if (content == null) return null;
        if (content instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException ex) {
            return content.toString();
        }
    }

    private static boolean isMemoryOrigin(String toolName) {
        if (toolName == null) return false;
        if (MEMORY_SKILL_NAMES.contains(toolName)) return true;
        String lower = toolName.toLowerCase(Locale.ROOT);
        return lower.startsWith("memory_");
    }

    // ───────────────────────────── window limit ─────────────────────────────

    private long resolveWindowLimit(AgentDefinition agentDef, String modelId) {
        Map<String, Object> cfg = agentDef.getConfig();
        Object override = cfg == null ? null : cfg.get("context_window_tokens");
        if (override instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        return ModelConfig.lookupKnownContextWindow(modelId)
                .map(Integer::longValue)
                .orElse((long) ModelConfig.DEFAULT_CONTEXT_WINDOW_TOKENS);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static long sumTokens(List<Segment> segs) {
        long total = 0L;
        for (Segment s : segs) total += s.tokens();
        return total;
    }

    private record SystemPromptSegments(
            List<Segment> segments, String stableHash, String assemblyHash) {
    }

    private record ToolSchemasBreakdown(Segment segment, String hash) {
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Provider callbacks (memories / CLAUDE.md) may hit I/O or external services —
     * a failure there shouldn't fail the breakdown call.
     */
    private <T> T safeProviderCall(java.util.function.Supplier<T> sup) {
        try {
            return sup.get();
        } catch (RuntimeException ex) {
            // Log only the exception class — user memories / CLAUDE.md may surface PII in messages.
            log.debug("context provider callback failed: {}", ex.getClass().getSimpleName());
            return null;
        }
    }
}
