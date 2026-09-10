package com.skillforge.server.history.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryAuthorizedProjectionTest {

    private final HistoryAuthorizedProjection projection =
            new HistoryAuthorizedProjection(new ObjectMapper());

    @Test
    void projectStructured_recursivelyRedactsSecretKeysAndSortsObjects() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", List.of(Map.of("api_key", "sk-live-secret")));
        first.put("password", "hunter2");
        first.put("a", "safe");

        HistoryAuthorizedProjection.ProjectedContent projected = projection.projectStructured(first);

        assertThat(projected.content()).isEqualTo(
                "{\"a\":\"safe\",\"password\":\"[REDACTED]\","
                        + "\"z\":[{\"api_key\":\"[REDACTED]\"}]}");
        assertThat(projected.authorizedContentHash()).hasSize(64);
        assertThat(projection.projectStructured(Map.of(
                "a", "safe", "password", "other", "z", List.of(Map.of("api_key", "other"))))
                .authorizedContentHash()).isEqualTo(projected.authorizedContentHash());
    }

    @Test
    void projectToolResult_redactsJsonBeforeHashAndBoundedPlainCredentials() {
        HistoryAuthorizedProjection.ProjectedContent json = projection.projectToolResult(
                "{\"nested\":{\"accessToken\":\"abc123456789\"},\"ok\":true}");
        HistoryAuthorizedProjection.ProjectedContent plain = projection.projectToolResult(
                "Authorization: Bearer abcdefghijklmnop password=hunter2");

        assertThat(json.content()).isEqualTo(
                "{\"nested\":{\"accessToken\":\"[REDACTED]\"},\"ok\":true}");
        assertThat(plain.content()).doesNotContain("abcdefghijklmnop", "hunter2")
                .contains("[REDACTED]");
    }

    @Test
    void projectToolResult_textNodeUsesSameRecursiveRedactionAsArchiveString() {
        String raw = "{\"nested\":[{\"password\":\"review-fixture-secret\"}],\"ok\":true}";
        var textNode = new ObjectMapper().getNodeFactory().textNode(raw);

        var original = projection.projectToolResult(textNode, false, null);
        var archive = projection.projectToolResult(raw, false, null);

        assertThat(original).isEqualTo(archive);
        assertThat(projection.projectToolResult(textNode)).isEqualTo(projection.projectToolResult(raw));
        assertThat(original.content()).doesNotContain("review-fixture-secret")
                .contains("[REDACTED]");
    }

    @Test
    void projectToolResult_preservesErrorStateInsideAuthorizedContent() {
        HistoryAuthorizedProjection.ProjectedContent projected =
                projection.projectToolResult("failed", true, "EXECUTION");

        assertThat(projected.content()).isEqualTo(
                "{\"content\":\"failed\",\"errorType\":\"EXECUTION\",\"isError\":true}");
    }

    @Test
    void projectPlainText_preservesNonBmpContentWithoutUtf16Truncation() {
        String content = "😀".repeat(600_000) + "末尾";

        HistoryAuthorizedProjection.ProjectedContent projected =
                projection.projectPlainText(content);

        assertThat(projected.content()).isEqualTo(content).endsWith("末尾");
    }
}
