package com.skillforge.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.compact.RequestTokenEstimator;
import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.context.ContextProvider;
import com.skillforge.core.llm.ModelConfig;
import com.skillforge.core.model.AgentDefinition;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.SkillDefinition;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillRegistry;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.view.SessionSkillResolver;
import com.skillforge.core.skill.view.SessionSkillView;
import com.skillforge.server.dto.ContextBreakdownDto;
import com.skillforge.server.dto.ContextBreakdownDto.Segment;
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
                    + "- Use FileRead instead of running `cat` or `head` via Bash\n"
                    + "- Use Glob instead of running `find` or `ls` via Bash\n"
                    + "- Use Grep instead of running `grep` or `rg` via Bash\n"
                    + "- Use FileEdit for modifying existing files instead of FileWrite\n"
                    + "- Always read a file before editing or overwriting it\n"
                    + "- Use absolute file paths whenever possible\n";

    private final AgentService agentService;
    private final SessionService sessionService;
    private final SkillRegistry skillRegistry;
    private final MemoryService memoryService;
    private final UserConfigService userConfigService;
    private final List<ContextProvider> contextProviders;
    private final ObjectMapper objectMapper;
    /** Plan r2 §5 — view 接管 skill 列表渲染（修复 B-BE-3 残留 getAllSkillDefinitions）。 */
    private final SessionSkillResolver sessionSkillResolver;

    public ContextBreakdownService(AgentService agentService,
                                   SessionService sessionService,
                                   SkillRegistry skillRegistry,
                                   MemoryService memoryService,
                                   UserConfigService userConfigService,
                                   List<ContextProvider> contextProviders,
                                   ObjectMapper objectMapper,
                                   SessionSkillResolver sessionSkillResolver) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.skillRegistry = skillRegistry;
        this.memoryService = memoryService;
        this.userConfigService = userConfigService;
        this.contextProviders = contextProviders != null ? contextProviders : List.of();
        this.objectMapper = objectMapper;
        this.sessionSkillResolver = sessionSkillResolver;
    }

    @Transactional(readOnly = true)
    public ContextBreakdownDto breakdown(SessionEntity session, Long userId) {
        AgentEntity agentEntity = agentService.getAgent(session.getAgentId());
        AgentDefinition agentDef = agentService.toAgentDefinition(agentEntity);
        String sessionId = session.getId();

        List<Segment> systemPromptChildren = buildSystemPromptSegments(agentDef, userId, session);
        long systemPromptTotal = sumTokens(systemPromptChildren);

        long toolSchemasTokens = estimateToolSchemasTokens();
        Segment toolSchemas = Segment.leaf(
                "tool_schemas", "Tool schemas (JSON)", toolSchemasTokens);

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
        segments.add(outputReservedSeg);

        long total = sumTokens(segments);
        long windowLimit = resolveWindowLimit(agentDef, agentEntity.getModelId());
        int pct = windowLimit > 0
                ? (int) Math.min(100L, Math.round(total * 100.0 / windowLimit))
                : 0;

        return new ContextBreakdownDto(sessionId, total, windowLimit, pct, segments);
    }

    // ─────────────────────────── system prompt segments ───────────────────────────

    private List<Segment> buildSystemPromptSegments(AgentDefinition agentDef,
                                                    Long userId,
                                                    SessionEntity session) {
        List<Segment> out = new ArrayList<>();

        String claudeMd = safeProviderCall(() -> userConfigService.getClaudeMd(userId));
        if (isNotBlank(claudeMd)) {
            out.add(Segment.leaf("claude_md", "CLAUDE.md",
                    TokenEstimator.estimateString(claudeMd)));
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

        String skillsBlock = renderSkillsListBlock(agentDef);
        if (!skillsBlock.isEmpty()) {
            out.add(Segment.leaf("skills_list", "Skills list",
                    TokenEstimator.estimateString(skillsBlock)));
        }

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

        return out;
    }

    /**
     * Plan r2 §5 — list only the skills authorised for this session (matches what
     * AgentLoopEngine actually injects into the system prompt). Resolver failure /
     * absent agent → fall back to {@link SessionSkillView#EMPTY} (fail-secure).
     * <p>Package-private for {@code ContextBreakdownServiceTest}.
     */
    String renderSkillsListBlock(AgentDefinition agentDef) {
        Collection<SkillDefinition> defs;
        try {
            SessionSkillView view = sessionSkillResolver != null
                    ? sessionSkillResolver.resolveFor(agentDef)
                    : SessionSkillView.EMPTY;
            defs = view != null ? view.all() : List.of();
        } catch (Exception e) {
            log.warn("renderSkillsListBlock: resolver failed, treating as empty: {}", e.getMessage());
            defs = List.of();
        }
        if (defs == null || defs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills\n\n");
        for (SkillDefinition d : defs) {
            sb.append("- **").append(nullSafe(d.getName())).append("**: ")
                    .append(nullSafe(d.getDescription())).append("\n");
        }
        return sb.toString();
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

    private long estimateToolSchemasTokens() {
        Collection<Tool> skills = skillRegistry.getAllTools();
        if (skills == null || skills.isEmpty()) return 0L;
        // CTX-1 — collect ToolSchemas first then delegate to RequestTokenEstimator so
        // the dashboard count and the engine's compact-trigger ratio share one
        // algorithm (FR-1.1 / AC-1). Schema fetch errors continue to be skipped silently
        // (preserve historical behaviour).
        List<ToolSchema> schemas = new ArrayList<>(skills.size());
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
        return RequestTokenEstimator.estimateToolSchemas(schemas, objectMapper);
    }

    // ───────────────────────────── messages ─────────────────────────────

    private List<Segment> bucketMessages(List<Message> messages) {
        long textTokens = 0L;
        long toolUseTokens = 0L;
        long memoryResultTokens = 0L;
        long otherResultTokens = 0L;

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
                            otherResultTokens += t;
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
        out.add(Segment.leaf("tool_result_other", "Other tool results", otherResultTokens));
        return out;
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
