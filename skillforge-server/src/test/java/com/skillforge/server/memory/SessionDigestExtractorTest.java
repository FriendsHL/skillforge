package com.skillforge.server.memory;

import com.skillforge.core.model.Message;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.ActivityLogEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionRepository;
import com.skillforge.server.service.ActivityLogService;
import com.skillforge.server.service.MemoryService;
import com.skillforge.server.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionDigestExtractor tests for LLM/rule mode switching and fallback.
 * Uses manual stubs and JDK proxies to avoid Java 25 Mockito limitations.
 */
class SessionDigestExtractorTest {

    private MemoryProperties memoryProperties;
    private SessionEntity currentSession;
    private SessionEntity lastSaved;
    private List<ActivityLogEntity> activities;
    private List<Message> messages;
    private final List<String[]> createdMemories = new ArrayList<>();
    private Long consolidatedUserId;

    // LLM extractor tracking
    private boolean llmExtractorCalled;
    private int llmExtractorReturnCount;
    private RuntimeException llmExtractorThrow;

    private SessionDigestExtractor extractor;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        currentSession = null;
        lastSaved = null;
        activities = List.of();
        messages = List.of();
        createdMemories.clear();
        consolidatedUserId = null;
        llmExtractorCalled = false;
        llmExtractorReturnCount = 0;
        llmExtractorThrow = null;

        // Proxy for SessionRepository (JPA interface)
        SessionRepository sessionRepository = (SessionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{SessionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(currentSession);
                    case "save" -> {
                        lastSaved = (SessionEntity) args[0];
                        yield lastSaved;
                    }
                    default -> null;
                }
        );

        // Stub services
        SessionService sessionService = new SessionService(null, null, null, null, null, null) {
            @Override
            public List<Message> getSessionMessages(String sessionId) {
                return messages;
            }
        };

        ActivityLogService activityLogService = new ActivityLogService(null) {
            @Override
            public List<ActivityLogEntity> getSessionActivities(String sessionId) {
                return activities;
            }
        };

        MemoryService memoryService = new MemoryService(null, null, null, null) {
            @Override
            public String beginExtractionBatch(Long userId) {
                return "batch-test";
            }

            @Override
            public void createMemoryIfNotDuplicate(Long userId, String type, String title, String content, String tags) {
                createdMemories.add(new String[]{userId.toString(), type, title, content, tags});
            }

            @Override
            public void createMemoryIfNotDuplicate(Long userId, String type, String title,
                                                   String content, String tags,
                                                   String extractionBatchId) {
                createdMemories.add(new String[]{userId.toString(), type, title, content, tags, extractionBatchId});
            }
        };

        MemoryConsolidator memoryConsolidator = new MemoryConsolidator(null) {
            @Override
            public void consolidate(Long userId) {
                consolidatedUserId = userId;
            }
        };

        LlmMemoryExtractor llmMemoryExtractor = new LlmMemoryExtractor(null, null, null, null, null) {
            @Override
            public int extract(SessionEntity session, List<ActivityLogEntity> acts, List<Message> msgs,
                               String extractionBatchId) {
                llmExtractorCalled = true;
                if (llmExtractorThrow != null) throw llmExtractorThrow;
                return llmExtractorReturnCount;
            }
        };

        extractor = new SessionDigestExtractor(
                sessionRepository, sessionService, activityLogService,
                memoryService, memoryConsolidator, memoryProperties, llmMemoryExtractor
        );
    }

    private SessionEntity makeSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(1L);
        s.setTitle("Test Session");
        return s;
    }

    private List<ActivityLogEntity> makeActivities(int count) {
        List<ActivityLogEntity> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ActivityLogEntity a = new ActivityLogEntity();
            a.setToolName("Tool" + i);
            a.setInputSummary("input " + i);
            a.setSuccess(true);
            list.add(a);
        }
        return list;
    }

    @Test
    @DisplayName("rule mode: uses rule-based extraction by default")
    void triggerAsync_ruleMode_usesRuleBased() {
        currentSession = makeSession("sess-rule");
        activities = makeActivities(5);
        messages = List.of(Message.user("Hello"), Message.assistant("Hi"));

        extractor.triggerExtractionAsync("sess-rule");

        assertThat(createdMemories).hasSize(1);
        assertThat(createdMemories.get(0)[4]).isEqualTo("auto-extract");
        assertThat(llmExtractorCalled).isFalse();
        assertThat(consolidatedUserId).isEqualTo(1L);
        assertThat(currentSession.getDigestExtractedAt()).isNotNull();
    }

    @Test
    @DisplayName("llm mode: delegates to LlmMemoryExtractor")
    void triggerAsync_llmMode_delegatesToLlm() {
        memoryProperties.setExtractionMode("llm");
        currentSession = makeSession("sess-llm");
        activities = makeActivities(5);
        messages = List.of(Message.user("Hello"), Message.assistant("Hi"));
        llmExtractorReturnCount = 3;

        extractor.triggerExtractionAsync("sess-llm");

        assertThat(llmExtractorCalled).isTrue();
        assertThat(createdMemories).isEmpty(); // rule-based NOT called
        assertThat(consolidatedUserId).isEqualTo(1L);
    }

    @Test
    @DisplayName("llm mode fallback: falls back to rule-based on LLM failure")
    void triggerAsync_llmFails_fallsBackToRule() {
        memoryProperties.setExtractionMode("llm");
        currentSession = makeSession("sess-fallback");
        activities = makeActivities(5);
        messages = List.of(Message.user("Hello"), Message.assistant("Hi"));
        llmExtractorThrow = new RuntimeException("LLM unavailable");

        extractor.triggerExtractionAsync("sess-fallback");

        assertThat(llmExtractorCalled).isTrue();
        assertThat(createdMemories).hasSize(1);
        assertThat(createdMemories.get(0)[4]).isEqualTo("auto-extract");
        assertThat(consolidatedUserId).isEqualTo(1L);
    }

    @Test
    @DisplayName("skips child sessions (parentSessionId != null)")
    void triggerAsync_childSession_skips() {
        currentSession = makeSession("sess-child");
        currentSession.setParentSessionId("parent-id");

        extractor.triggerExtractionAsync("sess-child");

        assertThat(createdMemories).isEmpty();
        assertThat(llmExtractorCalled).isFalse();
        assertThat(consolidatedUserId).isNull();
    }

    @Test
    @DisplayName("skips already extracted sessions")
    void triggerAsync_alreadyExtracted_skips() {
        currentSession = makeSession("sess-done");
        currentSession.setDigestExtractedAt(Instant.now());

        extractor.triggerExtractionAsync("sess-done");

        assertThat(createdMemories).isEmpty();
        assertThat(llmExtractorCalled).isFalse();
    }

    @Test
    @DisplayName("skips sessions with < 3 activities")
    void triggerAsync_fewActivities_skips() {
        currentSession = makeSession("sess-short");
        activities = makeActivities(2);

        extractor.triggerExtractionAsync("sess-short");

        assertThat(createdMemories).isEmpty();
        assertThat(llmExtractorCalled).isFalse();
    }
}
