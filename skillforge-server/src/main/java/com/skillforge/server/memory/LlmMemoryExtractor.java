package com.skillforge.server.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.ActivityLogEntity;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM-based memory extractor (Phase 3).
 * Analyzes session conversation history via LLM to extract structured,
 * categorized memory entries.
 */
@Component
public class LlmMemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractor.class);

    private static final Set<String> VALID_TYPES = Set.of(
            "knowledge", "preference", "feedback", "project", "reference"
    );
    private static final Set<String> VALID_IMPORTANCE = Set.of("high", "medium", "low");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_CONTENT_LENGTH = 2000;

    private static final String SYSTEM_PROMPT = """
            You are a memory extraction specialist for an AI agent platform. Your job is to analyze \
            a completed conversation session and extract key facts, insights, and user preferences \
            that would be valuable to remember for future interactions.

            ## Output Format

            Output ONLY a JSON array (no markdown fences, no explanation). Each element:
            ```
            {
              "type": "<category>",
              "title": "<short descriptive title, 5-15 words>",
              "content": "<memory content, 1-3 sentences>",
              "importance": "<high|medium|low>"
            }
            ```

            ## Categories (type field)

            - **knowledge**: Technical facts, solutions, code patterns, architecture decisions discovered during the session
            - **preference**: User preferences about tools, workflows, coding style, communication style
            - **feedback**: User corrections, complaints, or positive feedback about agent behavior
            - **project**: Project-specific context: goals, deadlines, team structure, business constraints
            - **reference**: External resources, URLs, documentation, API endpoints mentioned

            ## Importance Levels

            - **high**: Critical facts that would cause errors or significant misunderstanding if forgotten
            - **medium**: Useful context that improves response quality
            - **low**: Nice-to-know background information

            ## Rules

            1. Extract 1-8 memories per session. Quality over quantity.
            2. Each memory should be self-contained and useful without the original conversation context.
            3. Do NOT extract trivial greetings, acknowledgments, or generic chat.
            4. Do NOT extract information that is obvious from code or git history.
            5. Focus on insights that would be lost if the conversation were deleted.
            6. Title should be unique and descriptive enough to identify the memory.
            7. Content should be concise but complete — 1-3 sentences max.
            8. If the session has no meaningful extractable information, output an empty array: []""";

    private final LlmProviderFactory llmProviderFactory;
    private final LlmProperties llmProperties;
    private final MemoryProperties memoryProperties;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public LlmMemoryExtractor(LlmProviderFactory llmProviderFactory,
                               LlmProperties llmProperties,
                               MemoryProperties memoryProperties,
                               MemoryService memoryService,
                               ObjectMapper objectMapper) {
        this.llmProviderFactory = llmProviderFactory;
        this.llmProperties = llmProperties;
        this.memoryProperties = memoryProperties;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    /**
     * Extract memories from a completed session using LLM.
     *
     * @param session    the completed session
     * @param activities activity log entries for the session
     * @param messages   conversation messages
     * @return number of memories created
     */
    public int extract(SessionEntity session, List<ActivityLogEntity> activities, List<Message> messages) {
        return extract(session, activities, messages, null);
    }

    public int extract(SessionEntity session, List<ActivityLogEntity> activities, List<Message> messages,
                       String extractionBatchId) {
        String providerName = resolveProviderName();
        LlmProvider provider = llmProviderFactory.getProvider(providerName);
        if (provider == null) {
            log.error("LlmMemoryExtractor: no provider available for name={}", providerName);
            return 0;
        }

        String userMessage = buildUserMessage(session, activities, messages);
        LlmRequest request = buildRequest(userMessage);
        LlmResponse response = provider.chat(request);

        String content = response.getContent();
        if (content == null || content.isBlank()) {
            log.warn("LlmMemoryExtractor: empty response for session={}", session.getId());
            return 0;
        }

        List<ExtractedMemoryEntry> entries = parseResponse(content);
        return storeEntries(session.getUserId(), entries, extractionBatchId);
    }

    private String resolveProviderName() {
        String configured = memoryProperties.getExtractionProvider();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String defaultProvider = llmProperties.getDefaultProvider();
        return defaultProvider != null ? defaultProvider : "claude";
    }

    private String buildUserMessage(SessionEntity session,
                                     List<ActivityLogEntity> activities,
                                     List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Session Metadata\n\n");
        sb.append("- Session ID: ").append(session.getId()).append("\n");
        if (session.getTitle() != null) {
            sb.append("- Title: ").append(session.getTitle()).append("\n");
        }
        sb.append("- Message count: ").append(messages.size()).append("\n");
        sb.append("- Tool calls: ").append(activities.size()).append("\n\n");

        // Activity summary (compact)
        if (!activities.isEmpty()) {
            sb.append("## Tool Activity Summary\n\n");
            for (ActivityLogEntity a : activities) {
                sb.append("- [").append(a.getToolName()).append("] ");
                if (a.getInputSummary() != null) {
                    sb.append(a.getInputSummary());
                }
                sb.append(" -> ").append(a.isSuccess() ? "OK" : "FAIL").append("\n");
            }
            sb.append("\n");
        }

        // Conversation history (truncated)
        sb.append("## Conversation History\n\n");
        int maxChars = memoryProperties.getMaxConversationChars();
        int charBudget = maxChars;
        for (Message m : messages) {
            String text = m.getTextContent();
            if (text == null || text.isBlank()) continue;
            String role = m.getRole() != null ? m.getRole().name().toLowerCase() : "unknown";
            String line = "[" + role + "] " + text + "\n\n";
            if (line.length() > charBudget) {
                if (charBudget > 100) {
                    sb.append("[").append(role).append("] ")
                      .append(text, 0, Math.min(text.length(), charBudget - 50))
                      .append("...[truncated]\n\n");
                }
                break;
            }
            sb.append(line);
            charBudget -= line.length();
        }

        // Existing memories context for deduplication
        List<MemoryEntity> existing = memoryService.listMemories(session.getUserId(), null);
        if (!existing.isEmpty()) {
            sb.append("## Existing Memories (do NOT duplicate these)\n\n");
            String titles = existing.stream()
                    .map(MemoryEntity::getTitle)
                    .filter(t -> t != null && !t.isBlank())
                    .collect(Collectors.joining(", "));
            sb.append(titles).append("\n");
        }

        return sb.toString();
    }

    private LlmRequest buildRequest(String userMessage) {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(SYSTEM_PROMPT);
        List<Message> messages = new ArrayList<>();
        messages.add(Message.user(userMessage));
        request.setMessages(messages);
        request.setMaxTokens(2000);
        request.setTemperature(0.3);
        return request;
    }

    List<ExtractedMemoryEntry> parseResponse(String content) {
        // Strip markdown fences if present
        String json = content.trim();
        Matcher matcher = MARKDOWN_FENCE.matcher(json);
        if (matcher.find()) {
            json = matcher.group(1).trim();
        }

        try {
            List<ExtractedMemoryEntry> entries = objectMapper.readValue(
                    json, new TypeReference<List<ExtractedMemoryEntry>>() {});
            return entries.stream()
                    .filter(this::isValid)
                    .toList();
        } catch (Exception e) {
            log.warn("LlmMemoryExtractor: failed to parse LLM response: {}", e.getMessage());
            log.debug("LlmMemoryExtractor: raw response: {}", content);
            return List.of();
        }
    }

    private boolean isValid(ExtractedMemoryEntry entry) {
        if (entry.type() == null || !VALID_TYPES.contains(entry.type().toLowerCase())) {
            log.debug("LlmMemoryExtractor: invalid type={}", entry.type());
            return false;
        }
        if (entry.title() == null || entry.title().isBlank()) {
            return false;
        }
        if (entry.content() == null || entry.content().isBlank()) {
            return false;
        }
        if (entry.importance() != null && !VALID_IMPORTANCE.contains(entry.importance().toLowerCase())) {
            log.debug("LlmMemoryExtractor: invalid importance={}", entry.importance());
            return false;
        }
        return true;
    }

    private int storeEntries(Long userId, List<ExtractedMemoryEntry> entries, String extractionBatchId) {
        int stored = 0;
        for (ExtractedMemoryEntry entry : entries) {
            try {
                String title = truncate(entry.title(), MAX_TITLE_LENGTH);
                String content = truncate(entry.content(), MAX_CONTENT_LENGTH);
                String type = entry.type().toLowerCase();
                String importance = entry.importance() != null ? entry.importance().toLowerCase() : "medium";
                String tags = "auto-extract,llm,importance:" + importance;

                memoryService.createMemoryIfNotDuplicate(userId, type, title, content, tags, extractionBatchId);
                stored++;
            } catch (Exception e) {
                log.warn("LlmMemoryExtractor: failed to store entry title={}: {}",
                        entry.title(), e.getMessage());
            }
        }
        log.info("LlmMemoryExtractor: stored {}/{} entries for userId={}", stored, entries.size(), userId);
        return stored;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
