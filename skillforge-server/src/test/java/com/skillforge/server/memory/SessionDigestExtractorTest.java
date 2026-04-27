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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionDigestExtractor tests for Memory v2 incremental triggering.
 * Uses manual stubs and JDK proxies to avoid Java 25 Mockito limitations.
 */
class SessionDigestExtractorTest {

    private MemoryProperties memoryProperties;
    private SessionEntity currentSession;
    private SessionEntity lastSaved;
    private List<ActivityLogEntity> activities;
    private List<SessionService.StoredMessage> storedMessages;
    private final List<String[]> createdMemories = new ArrayList<>();
    private Long consolidatedUserId;

    private boolean llmExtractorCalled;
    private int llmExtractorReturnCount;
    private RuntimeException llmExtractorThrow;
    private long capturedFromSeq;
    private long capturedToSeq;

    private SessionDigestExtractor extractor;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        currentSession = null;
        lastSaved = null;
        activities = List.of();
        storedMessages = List.of();
        createdMemories.clear();
        consolidatedUserId = null;
        llmExtractorCalled = false;
        llmExtractorReturnCount = 0;
        llmExtractorThrow = null;
        capturedFromSeq = -1L;
        capturedToSeq = -1L;

        SessionRepository sessionRepository = (SessionRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{SessionRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByIdForUpdate", "findById" -> Optional.ofNullable(currentSession);
                    case "save" -> {
                        lastSaved = (SessionEntity) args[0];
                        yield lastSaved;
                    }
                    default -> null;
                }
        );

        SessionService sessionService = new SessionService(null, null, null, null, null, null) {
            @Override
            public List<StoredMessage> getNormalHistoryRecordsAfterSeq(String sessionId, long seqNoExclusive) {
                return storedMessages.stream()
                        .filter(stored -> stored.seqNo() > seqNoExclusive)
                        .toList();
            }

            @Override
            public long getLatestNormalSeqNo(String sessionId) {
                return storedMessages.stream()
                        .mapToLong(StoredMessage::seqNo)
                        .max()
                        .orElse(-1L);
            }

            @Override
            public long countUserNormalMessagesAfterSeq(String sessionId, long seqNoExclusive) {
                return storedMessages.stream()
                        .filter(stored -> stored.seqNo() > seqNoExclusive)
                        .filter(stored -> stored.message().getRole() == Message.Role.USER)
                        .count();
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
            public int extract(SessionEntity session,
                               List<ActivityLogEntity> acts,
                               List<Message> msgs,
                               String extractionBatchId,
                               long fromSeq,
                               long toSeq) {
                llmExtractorCalled = true;
                capturedFromSeq = fromSeq;
                capturedToSeq = toSeq;
                if (llmExtractorThrow != null) throw llmExtractorThrow;
                return llmExtractorReturnCount;
            }
        };

        extractor = new SessionDigestExtractor(
                sessionRepository, sessionService, activityLogService,
                memoryService, memoryConsolidator, memoryProperties, llmMemoryExtractor, null
        );
    }

    private SessionEntity makeSession(String id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setUserId(1L);
        s.setTitle("Test Session");
        return s;
    }

    private void setStoredMessages(Message... messages) {
        List<SessionService.StoredMessage> records = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            records.add(new SessionService.StoredMessage(
                    i, SessionService.MSG_TYPE_NORMAL, Collections.emptyMap(), messages[i]));
        }
        storedMessages = records;
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

    private void setThreeUserTurns() {
        setStoredMessages(
                Message.user("u1"),
                Message.assistant("a1"),
                Message.user("u2"),
                Message.assistant("a2"),
                Message.user("u3"),
                Message.assistant("a3")
        );
    }

    @Test
    @DisplayName("rule mode: extracts incremental messages and advances cursor")
    void triggerNow_ruleMode_extractsIncrementalAndAdvancesCursor() {
        currentSession = makeSession("sess-rule");
        activities = makeActivities(2);
        setThreeUserTurns();

        SessionDigestExtractor.ExtractionResult result = extractor.triggerExtractionNow("sess-rule");

        assertThat(result.extracted()).isTrue();
        assertThat(result.fromSeq()).isZero();
        assertThat(result.toSeq()).isEqualTo(5L);
        assertThat(createdMemories).hasSize(1);
        assertThat(createdMemories.get(0)[4]).isEqualTo("auto-extract");
        assertThat(llmExtractorCalled).isFalse();
        assertThat(consolidatedUserId).isEqualTo(1L);
        assertThat(currentSession.getLastExtractedMessageSeq()).isEqualTo(5L);
        assertThat(currentSession.getDigestExtractedAt()).isNotNull();
        assertThat(lastSaved).isSameAs(currentSession);
    }

    @Test
    @DisplayName("llm mode: delegates incremental seq metadata to LlmMemoryExtractor")
    void triggerNow_llmMode_delegatesToLlmWithSeqRange() {
        memoryProperties.setExtractionMode("llm");
        currentSession = makeSession("sess-llm");
        currentSession.setDigestExtractedAt(Instant.now().minusSeconds(600));
        currentSession.setLastExtractedMessageSeq(1L);
        setStoredMessages(
                Message.user("old"),
                Message.assistant("old answer"),
                Message.user("u1"),
                Message.user("u2"),
                Message.user("u3")
        );
        llmExtractorReturnCount = 3;

        SessionDigestExtractor.ExtractionResult result = extractor.triggerExtractionNow("sess-llm");

        assertThat(result.extracted()).isTrue();
        assertThat(llmExtractorCalled).isTrue();
        assertThat(capturedFromSeq).isEqualTo(2L);
        assertThat(capturedToSeq).isEqualTo(4L);
        assertThat(createdMemories).isEmpty();
        assertThat(consolidatedUserId).isEqualTo(1L);
    }

    @Test
    @DisplayName("llm mode failure does not advance cursor or fall back to rule digest")
    void triggerNow_llmFails_skipsWithoutAdvancingCursor() {
        memoryProperties.setExtractionMode("llm");
        currentSession = makeSession("sess-fallback");
        setThreeUserTurns();
        llmExtractorThrow = new RuntimeException("LLM unavailable");

        SessionDigestExtractor.ExtractionResult result = extractor.triggerExtractionNow("sess-fallback");

        assertThat(result.extracted()).isFalse();
        assertThat(result.reason()).isEqualTo("llm-failed");
        assertThat(llmExtractorCalled).isTrue();
        assertThat(currentSession.getLastExtractedMessageSeq()).isZero();
        assertThat(currentSession.getDigestExtractedAt()).isNull();
        assertThat(createdMemories).isEmpty();
        assertThat(consolidatedUserId).isNull();
    }

    @Test
    @DisplayName("skips child sessions")
    void triggerNow_childSession_skips() {
        currentSession = makeSession("sess-child");
        currentSession.setParentSessionId("parent-id");
        setThreeUserTurns();

        SessionDigestExtractor.ExtractionResult result = extractor.triggerExtractionNow("sess-child");

        assertThat(result.extracted()).isFalse();
        assertThat(result.reason()).isEqualTo("child-session");
        assertThat(createdMemories).isEmpty();
        assertThat(llmExtractorCalled).isFalse();
        assertThat(consolidatedUserId).isNull();
    }

    @Test
    @DisplayName("cooldown skips recently extracted sessions")
    void triggerNow_recentlyExtracted_skipsByCooldown() {
        currentSession = makeSession("sess-cooldown");
        currentSession.setDigestExtractedAt(Instant.now());
        currentSession.setLastExtractedMessageSeq(0L);
        setStoredMessages(
                Message.user("old"),
                Message.user("u1"),
                Message.user("u2"),
                Message.user("u3")
        );

        SessionDigestExtractor.ExtractionResult result = extractor.triggerExtractionNow("sess-cooldown");

        assertThat(result.extracted()).isFalse();
        assertThat(result.reason()).isEqualTo("cooldown");
        assertThat(createdMemories).isEmpty();
        assertThat(llmExtractorCalled).isFalse();
    }

    @Test
    @DisplayName("max unextracted turns bypasses standard cooldown")
    void triggerNow_maxTurns_bypassesStandardCooldown() {
        memoryProperties.getExtraction().setMaxUnextractedTurns(3);
        currentSession = makeSession("sess-max-turns");
        currentSession.setDigestExtractedAt(Instant.now());
        currentSession.setLastExtractedMessageSeq(0L);
        setStoredMessages(
                Message.user("old"),
                Message.user("u1"),
                Message.user("u2"),
                Message.user("u3")
        );

        SessionDigestExtractor.ExtractionResult result = extractor.triggerExtractionNow("sess-max-turns");

        assertThat(result.extracted()).isTrue();
        assertThat(createdMemories).hasSize(1);
        assertThat(currentSession.getLastExtractedMessageSeq()).isEqualTo(3L);
    }

    @Test
    @DisplayName("skips sessions with fewer than 3 new user turns without advancing cursor")
    void triggerNow_fewUserTurns_skipsWithoutLockingSession() {
        currentSession = makeSession("sess-short");
        setStoredMessages(
                Message.user("u1"),
                Message.assistant("a1"),
                Message.user("u2")
        );

        SessionDigestExtractor.ExtractionResult result = extractor.triggerExtractionNow("sess-short");

        assertThat(result.extracted()).isFalse();
        assertThat(result.reason()).isEqualTo("not-enough-user-turns");
        assertThat(currentSession.getDigestExtractedAt()).isNull();
        assertThat(currentSession.getLastExtractedMessageSeq()).isZero();
        assertThat(createdMemories).isEmpty();
        assertThat(llmExtractorCalled).isFalse();
    }

    @Test
    @DisplayName("empty LLM result advances cursor and applies extended empty cooldown")
    void triggerNow_emptyLlmResult_advancesCursorAndExtendsCooldown() {
        memoryProperties.setExtractionMode("llm");
        currentSession = makeSession("sess-empty");
        setThreeUserTurns();
        llmExtractorReturnCount = 0;

        SessionDigestExtractor.ExtractionResult first = extractor.triggerExtractionNow("sess-empty");

        assertThat(first.extracted()).isTrue();
        assertThat(first.memoryCount()).isZero();
        assertThat(currentSession.getLastExtractedMessageSeq()).isEqualTo(5L);
        assertThat(consolidatedUserId).isNull();

        setStoredMessages(
                Message.user("u1"),
                Message.assistant("a1"),
                Message.user("u2"),
                Message.assistant("a2"),
                Message.user("u3"),
                Message.assistant("a3"),
                Message.user("u4"),
                Message.user("u5"),
                Message.user("u6")
        );
        llmExtractorCalled = false;

        SessionDigestExtractor.ExtractionResult second = extractor.triggerExtractionNow("sess-empty");

        assertThat(second.extracted()).isFalse();
        assertThat(second.reason()).isEqualTo("cooldown");
        assertThat(llmExtractorCalled).isFalse();
    }
}
