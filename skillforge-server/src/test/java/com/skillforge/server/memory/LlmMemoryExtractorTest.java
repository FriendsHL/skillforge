package com.skillforge.server.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.Message;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.config.MemoryProperties;
import com.skillforge.server.entity.ActivityLogEntity;
import com.skillforge.server.entity.MemoryEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmMemoryExtractor unit tests.
 * Uses manual stubs (no Mockito) to avoid Java 25 byte-buddy restrictions.
 */
class LlmMemoryExtractorTest {

    private ObjectMapper objectMapper;
    private MemoryProperties memoryProperties;
    private LlmProperties llmProperties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        memoryProperties = new MemoryProperties();
        memoryProperties.setExtractionMode("llm");
        llmProperties = new LlmProperties();
        llmProperties.setDefaultProvider("test-provider");
    }

    private LlmMemoryExtractor buildExtractor(LlmProviderFactory factory, MemoryService memoryService) {
        return new LlmMemoryExtractor(factory, llmProperties, memoryProperties, memoryService, objectMapper);
    }

    private SessionEntity makeSession() {
        SessionEntity session = new SessionEntity();
        session.setId("sess-001");
        session.setUserId(1L);
        session.setTitle("Test Session");
        return session;
    }

    private List<ActivityLogEntity> makeActivities() {
        ActivityLogEntity a = new ActivityLogEntity();
        a.setToolName("FileRead");
        a.setInputSummary("read config.yml");
        a.setSuccess(true);
        return List.of(a);
    }

    private List<Message> makeMessages() {
        return List.of(
                Message.user("Help me set up the database connection"),
                Message.assistant("I'll configure the PostgreSQL connection for you.")
        );
    }

    // --- Stubs ---

    /** Stub LlmProvider that returns a fixed response. */
    static class StubProvider implements LlmProvider {
        private final String responseContent;
        LlmRequest capturedRequest;

        StubProvider(String responseContent) {
            this.responseContent = responseContent;
        }

        @Override public String getName() { return "stub"; }

        @Override
        public LlmResponse chat(LlmRequest request) {
            this.capturedRequest = request;
            LlmResponse resp = new LlmResponse();
            resp.setContent(responseContent);
            resp.setStopReason("end_turn");
            return resp;
        }

        @Override
        public void chatStream(LlmRequest request, LlmStreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    /** Stub MemoryService that records createMemoryIfNotDuplicate calls. */
    static class RecordingMemoryService extends MemoryService {
        final List<String[]> createdMemories = new ArrayList<>();
        private final List<MemoryEntity> existingMemories;

        RecordingMemoryService(List<MemoryEntity> existingMemories) {
            super(null, null, null, null);
            this.existingMemories = existingMemories;
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

        @Override
        public List<MemoryEntity> listMemories(Long userId, String type) {
            return existingMemories;
        }
    }

    // --- Extract tests ---

    @Nested
    @DisplayName("extract()")
    class ExtractTests {

        @Test
        @DisplayName("extracts and stores valid memories from LLM response")
        void extract_validResponse_storesMemories() {
            String json = """
                    [
                      {"type": "knowledge", "title": "PostgreSQL config pattern", "content": "Project uses embedded PostgreSQL on port 15432.", "importance": "high"},
                      {"type": "preference", "title": "User prefers YAML config", "content": "User asked for YAML format over properties files.", "importance": "medium"}
                    ]""";

            StubProvider provider = new StubProvider(json);
            LlmProviderFactory factory = new LlmProviderFactory();
            factory.registerProvider("test-provider", provider);
            RecordingMemoryService memService = new RecordingMemoryService(List.of());

            LlmMemoryExtractor extractor = buildExtractor(factory, memService);
            int count = extractor.extract(makeSession(), makeActivities(), makeMessages());

            assertThat(count).isEqualTo(2);
            assertThat(memService.createdMemories).hasSize(2);

            String[] first = memService.createdMemories.get(0);
            assertThat(first[0]).isEqualTo("1"); // userId
            assertThat(first[1]).isEqualTo("knowledge");
            assertThat(first[2]).isEqualTo("PostgreSQL config pattern");
            assertThat(first[4]).isEqualTo("auto-extract,llm,importance:high");

            String[] second = memService.createdMemories.get(1);
            assertThat(second[1]).isEqualTo("preference");
            assertThat(second[4]).isEqualTo("auto-extract,llm,importance:medium");
        }

        @Test
        @DisplayName("returns 0 when provider is not available")
        void extract_noProvider_returnsZero() {
            LlmProviderFactory factory = new LlmProviderFactory(); // no provider registered
            RecordingMemoryService memService = new RecordingMemoryService(List.of());

            LlmMemoryExtractor extractor = buildExtractor(factory, memService);
            int count = extractor.extract(makeSession(), makeActivities(), makeMessages());

            assertThat(count).isZero();
            assertThat(memService.createdMemories).isEmpty();
        }

        @Test
        @DisplayName("returns 0 when LLM returns empty response")
        void extract_emptyResponse_returnsZero() {
            StubProvider provider = new StubProvider("");
            LlmProviderFactory factory = new LlmProviderFactory();
            factory.registerProvider("test-provider", provider);
            RecordingMemoryService memService = new RecordingMemoryService(List.of());

            LlmMemoryExtractor extractor = buildExtractor(factory, memService);
            int count = extractor.extract(makeSession(), makeActivities(), makeMessages());

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("returns 0 when LLM returns empty array")
        void extract_emptyArray_returnsZero() {
            StubProvider provider = new StubProvider("[]");
            LlmProviderFactory factory = new LlmProviderFactory();
            factory.registerProvider("test-provider", provider);
            RecordingMemoryService memService = new RecordingMemoryService(List.of());

            LlmMemoryExtractor extractor = buildExtractor(factory, memService);
            int count = extractor.extract(makeSession(), makeActivities(), makeMessages());

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("uses configured extraction-provider over default")
        void extract_customProvider_usesConfigured() {
            memoryProperties.setExtractionProvider("custom-llm");
            StubProvider provider = new StubProvider("[]");
            LlmProviderFactory factory = new LlmProviderFactory();
            factory.registerProvider("custom-llm", provider);
            RecordingMemoryService memService = new RecordingMemoryService(List.of());

            LlmMemoryExtractor extractor = buildExtractor(factory, memService);
            extractor.extract(makeSession(), makeActivities(), makeMessages());

            // Verify it called the custom provider (request captured)
            assertThat(provider.capturedRequest).isNotNull();
        }

        @Test
        @DisplayName("LLM request uses low temperature and bounded max tokens")
        void extract_requestParams_correctSettings() {
            StubProvider provider = new StubProvider("[]");
            LlmProviderFactory factory = new LlmProviderFactory();
            factory.registerProvider("test-provider", provider);
            RecordingMemoryService memService = new RecordingMemoryService(List.of());

            LlmMemoryExtractor extractor = buildExtractor(factory, memService);
            extractor.extract(makeSession(), makeActivities(), makeMessages());

            LlmRequest req = provider.capturedRequest;
            assertThat(req.getTemperature()).isEqualTo(0.3);
            assertThat(req.getMaxTokens()).isEqualTo(2000);
            assertThat(req.getSystemPrompt()).contains("memory extraction specialist");
            assertThat(req.getMessages()).hasSize(1);
        }

        @Test
        @DisplayName("includes existing memory titles in prompt for deduplication")
        void extract_existingMemories_includedInPrompt() {
            MemoryEntity existing = new MemoryEntity();
            existing.setTitle("Known pattern: retry logic");

            StubProvider provider = new StubProvider("[]");
            LlmProviderFactory factory = new LlmProviderFactory();
            factory.registerProvider("test-provider", provider);
            RecordingMemoryService memService = new RecordingMemoryService(List.of(existing));

            LlmMemoryExtractor extractor = buildExtractor(factory, memService);
            extractor.extract(makeSession(), makeActivities(), makeMessages());

            String userMsg = provider.capturedRequest.getMessages().get(0).getTextContent();
            assertThat(userMsg).contains("Known pattern: retry logic");
            assertThat(userMsg).contains("do NOT duplicate");
        }
    }

    // --- Parse response tests ---

    @Nested
    @DisplayName("parseResponse()")
    class ParseResponseTests {

        private LlmMemoryExtractor extractor;

        @BeforeEach
        void init() {
            LlmProviderFactory factory = new LlmProviderFactory();
            RecordingMemoryService memService = new RecordingMemoryService(List.of());
            extractor = buildExtractor(factory, memService);
        }

        @Test
        @DisplayName("parses clean JSON array")
        void parseResponse_cleanJson_parsesCorrectly() {
            String json = """
                    [{"type":"knowledge","title":"DB port","content":"Port 15432","importance":"high"}]""";

            List<ExtractedMemoryEntry> entries = extractor.parseResponse(json);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).type()).isEqualTo("knowledge");
            assertThat(entries.get(0).title()).isEqualTo("DB port");
            assertThat(entries.get(0).importance()).isEqualTo("high");
        }

        @Test
        @DisplayName("strips markdown fences before parsing")
        void parseResponse_markdownFences_stripsAndParses() {
            String json = """
                    ```json
                    [{"type":"preference","title":"Dark mode","content":"User prefers dark mode","importance":"low"}]
                    ```""";

            List<ExtractedMemoryEntry> entries = extractor.parseResponse(json);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).type()).isEqualTo("preference");
        }

        @Test
        @DisplayName("filters out entries with invalid type")
        void parseResponse_invalidType_filtered() {
            String json = """
                    [
                      {"type":"knowledge","title":"Valid","content":"OK","importance":"high"},
                      {"type":"invalid_type","title":"Bad","content":"Nope","importance":"low"}
                    ]""";

            List<ExtractedMemoryEntry> entries = extractor.parseResponse(json);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).title()).isEqualTo("Valid");
        }

        @Test
        @DisplayName("filters out entries with blank title or content")
        void parseResponse_blankFields_filtered() {
            String json = """
                    [
                      {"type":"knowledge","title":"","content":"OK","importance":"high"},
                      {"type":"knowledge","title":"Good","content":"","importance":"low"},
                      {"type":"knowledge","title":"Valid","content":"Content","importance":"medium"}
                    ]""";

            List<ExtractedMemoryEntry> entries = extractor.parseResponse(json);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).title()).isEqualTo("Valid");
        }

        @Test
        @DisplayName("returns empty list on malformed JSON")
        void parseResponse_malformedJson_returnsEmpty() {
            List<ExtractedMemoryEntry> entries = extractor.parseResponse("not valid json {{{");

            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("allows null importance (defaults to medium at store time)")
        void parseResponse_nullImportance_allowed() {
            String json = """
                    [{"type":"knowledge","title":"Test","content":"Content","importance":null}]""";

            List<ExtractedMemoryEntry> entries = extractor.parseResponse(json);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).importance()).isNull();
        }
    }
}
