package com.skillforge.server.tool.memorycontext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.MemoryInjection;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.memory.context.MemoryContextProvider;
import com.skillforge.server.memory.context.MemoryContextSnapshot;
import com.skillforge.server.service.MemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ListRelevantMemoriesTool")
class ListRelevantMemoriesToolTest {

    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private MemoryContextProvider provider;

    @Mock
    private MemoryService memoryService;

    @Test
    @DisplayName("provider: hash and ids come from read-only preview")
    void provider_hashAndIds_fromPreviewInjection() {
        MemoryContextProvider actualProvider = new MemoryContextProvider(memoryService);
        when(memoryService.previewMemoryInjectionForPrompt(42L, "reduce flaky tests"))
                .thenReturn(new MemoryInjection("### Preferences\n- test carefully\n",
                        new LinkedHashSet<>(List.of(7L, 9L))));

        MemoryContextSnapshot snapshot = actualProvider.load(42L, "reduce flaky tests");

        assertThat(snapshot.userId()).isEqualTo(42L);
        assertThat(snapshot.taskContext()).isEqualTo("reduce flaky tests");
        assertThat(snapshot.rendered()).isEqualTo("### Preferences\n- test carefully\n");
        assertThat(snapshot.memoryIds()).containsExactly(7L, 9L);
        assertThat(snapshot.contextHash())
                .isEqualTo("137099c699a04613fb25d383e32c2fc99f1df88071c0d482eb8e646bbe2ad301");
        verify(memoryService).previewMemoryInjectionForPrompt(42L, "reduce flaky tests");
    }

    @Test
    @DisplayName("provider: null injection parts become empty rendered text, empty ids, and empty-string hash")
    void provider_nullInjectionParts_emptySnapshot() {
        MemoryContextProvider actualProvider = new MemoryContextProvider(memoryService);
        when(memoryService.previewMemoryInjectionForPrompt(42L, "ctx"))
                .thenReturn(new MemoryInjection(null, null));

        MemoryContextSnapshot snapshot = actualProvider.load(42L, "ctx");

        assertThat(snapshot.rendered()).isEmpty();
        assertThat(snapshot.memoryIds()).isEmpty();
        assertThat(snapshot.contextHash()).isEqualTo(EMPTY_SHA256);
    }

    @Test
    @DisplayName("provider: null injection becomes empty rendered text, empty ids, and empty-string hash")
    void provider_nullInjection_emptySnapshot() {
        MemoryContextProvider actualProvider = new MemoryContextProvider(memoryService);
        when(memoryService.previewMemoryInjectionForPrompt(42L, "ctx")).thenReturn(null);

        MemoryContextSnapshot snapshot = actualProvider.load(42L, "ctx");

        assertThat(snapshot.rendered()).isEmpty();
        assertThat(snapshot.memoryIds()).isEmpty();
        assertThat(snapshot.contextHash()).isEqualTo(EMPTY_SHA256);
    }

    @Test
    @DisplayName("snapshot freezes memoryIds while preserving insertion order")
    void snapshot_memoryIds_defensivelyCopiedAndOrdered() throws Exception {
        LinkedHashSet<Long> originalIds = new LinkedHashSet<>(List.of(101L, 102L));
        MemoryContextSnapshot snapshot = new MemoryContextSnapshot(
                42L, "ctx", "rendered", originalIds, "hash");

        originalIds.add(103L);

        assertThat(snapshot.memoryIds()).containsExactly(101L, 102L);
        assertThatThrownBy(() -> snapshot.memoryIds().add(104L))
                .isInstanceOf(UnsupportedOperationException.class);

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(snapshot));
        assertThat(root.path("memoryIds").get(0).asLong()).isEqualTo(101L);
        assertThat(root.path("memoryIds").get(1).asLong()).isEqualTo(102L);
        assertThat(root.path("memoryIds")).hasSize(2);
    }

    @Test
    @DisplayName("missing userId returns specific validation error")
    void execute_missingUserId_validationError() {
        ListRelevantMemoriesTool tool = tool();

        SkillResult result = tool.execute(Map.of("taskContext", "ctx"), new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("userId", "positive integer");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("blank taskContext returns specific validation error")
    void execute_blankTaskContext_validationError() {
        ListRelevantMemoriesTool tool = tool();

        SkillResult result = tool.execute(Map.of("userId", 42L, "taskContext", "  "), new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorType()).isEqualTo(SkillResult.ErrorType.VALIDATION);
        assertThat(result.getError()).contains("taskContext", "non-blank string");
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("output shape includes snapshot fields")
    void execute_success_outputShape() throws Exception {
        when(provider.load(42L, "ship memory context"))
                .thenReturn(new MemoryContextSnapshot(
                        42L,
                        "ship memory context",
                        "### Knowledge & Context\n- [m101] Known issue",
                        new LinkedHashSet<>(List.of(101L, 102L)),
                        "abc123"));
        ListRelevantMemoriesTool tool = tool();

        SkillResult result = tool.execute(
                Map.of("userId", 42L, "taskContext", "ship memory context"),
                new SkillContext(null, "s1", 42L));

        assertThat(result.isSuccess()).isTrue();
        JsonNode root = objectMapper.readTree(result.getOutput());
        assertThat(root.path("userId").asLong()).isEqualTo(42L);
        assertThat(root.path("taskContext").asText()).isEqualTo("ship memory context");
        assertThat(root.path("rendered").asText()).contains("Known issue");
        assertThat(root.path("memoryIds")).hasSize(2);
        assertThat(root.path("memoryIds").get(0).asLong()).isEqualTo(101L);
        assertThat(root.path("memoryIds").get(1).asLong()).isEqualTo(102L);
        assertThat(root.path("contextHash").asText()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("provider exception returns sanitized generic error")
    void execute_providerThrows_returnsSanitizedError() {
        when(provider.load(42L, "ctx"))
                .thenThrow(new RuntimeException("SQL error: relation t_secret does not exist /tmp/foo"));
        ListRelevantMemoriesTool tool = tool();

        SkillResult result = tool.execute(Map.of("userId", 42L, "taskContext", "ctx"), new SkillContext());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("ListRelevantMemories failed; see server logs");
        assertThat(result.getError()).doesNotContain("SQL", "t_secret", "/tmp/foo");
    }

    private ListRelevantMemoriesTool tool() {
        return new ListRelevantMemoriesTool(provider, objectMapper);
    }
}
