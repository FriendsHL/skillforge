package com.skillforge.core.compact.recovery;

import com.skillforge.core.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryPayloadBuilderTest {

    private FileStateCache cache;
    private RecoveryPayloadBuilder builder;

    @BeforeEach
    void setUp() {
        cache = new FileStateCache();
        builder = new RecoveryPayloadBuilder(cache);
        builder.setEnabled(true);
        builder.setMaxFiles(5);
        builder.setMaxTokensPerFile(5_000);
    }

    @Test
    @DisplayName("empty cache returns null")
    void emptyCache_returnsNull() {
        assertThat(builder.build("s-empty")).isNull();
    }

    @Test
    @DisplayName("persistent contributor restores state even when file cache is empty")
    void contributorOnly_buildsRecoveryPayload() {
        builder.setContributors(java.util.List.of(sessionId -> "Task t1 is still in_progress"));
        Message message = builder.build("s-tasks");
        assertThat(message).isNotNull();
        assertThat((String) message.getContent()).contains("Task t1 is still in_progress", "<system-reminder>");
    }

    @Test
    @DisplayName("null/blank sessionId returns null")
    void blankSessionId_returnsNull() {
        cache.put("s1", "/x.txt", "y");
        assertThat(builder.build(null)).isNull();
        assertThat(builder.build("")).isNull();
    }

    @Test
    @DisplayName("disabled builder returns null even when cache has data")
    void disabled_returnsNull() {
        cache.put("s1", "/a.txt", "content");
        builder.setEnabled(false);
        assertThat(builder.build("s1")).isNull();
    }

    @Test
    @DisplayName("single file produces user message containing path and content")
    void singleFile_buildsUserMessage() {
        cache.put("s1", "/abs/path/foo.java", "public class Foo {}\n");

        Message msg = builder.build("s1");
        assertThat(msg).isNotNull();
        assertThat(msg.getRole()).isEqualTo(Message.Role.USER);
        String text = (String) msg.getContent();
        // REMINDER-MVP D6: payload is now wrapped in <system-reminder>...</system-reminder>.
        assertThat(text).startsWith("<system-reminder>\n");
        assertThat(text).endsWith("</system-reminder>\n");
        assertThat(text).contains("Recovery payload");
        assertThat(text).contains("/abs/path/foo.java");
        assertThat(text).contains("public class Foo {}");
        assertThat(text).contains("lastRead");
        assertThat(text).contains("lines ");
    }

    @Test
    @DisplayName("more files than maxFiles → truncated to maxFiles entries (most recent)")
    void exceedsMaxFiles_truncated() throws InterruptedException {
        builder.setMaxFiles(3);
        for (int i = 0; i < 6; i++) {
            cache.put("s1", "/file" + i + ".txt", "body " + i);
            Thread.sleep(2);
        }
        Message msg = builder.build("s1");
        assertThat(msg).isNotNull();
        String text = (String) msg.getContent();
        // REMINDER-MVP D6: payload is wrapped in <system-reminder>...</system-reminder>.
        assertThat(text).startsWith("<system-reminder>\n");
        assertThat(text).endsWith("</system-reminder>\n");
        // Must mention "3" in the header and contain the 3 most recent paths
        assertThat(text).contains("3 most recently accessed");
        assertThat(text).contains("/file5.txt");
        assertThat(text).contains("/file4.txt");
        assertThat(text).contains("/file3.txt");
        // Older ones must NOT appear
        assertThat(text).doesNotContain("/file0.txt");
    }

    @Test
    @DisplayName("output is a String content (not List<ContentBlock>)")
    void content_isString() {
        cache.put("s1", "/a.txt", "x");
        Message msg = builder.build("s1");
        assertThat(msg.getContent()).isInstanceOf(String.class);
    }
}
