package com.skillforge.server.tool.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.weixin.WeixinChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SendChannelFileTool} — path-containment guard (incl. cross-session +
 * symlink escape), oversized-file rejection, non-weixin graceful path, and the happy path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SendChannelFileToolTest {

    private static final String SESSION_ID = "sess-1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ChannelConversationRepository conversationRepository;
    @Mock
    private ChannelConfigService channelConfigService;
    @Mock
    private WeixinChannelAdapter weixinChannelAdapter;

    /**
     * Attachments root deliberately created OUTSIDE the system temp dir (under target/) so the
     * temp-dir allowlist half does not mask cross-session containment — the production root
     * ({@code ./data/chat-attachments}) is likewise not under temp.
     */
    private Path attachmentsRoot;

    /** This session's attachment subdir (attachmentsRoot/<sessionId>) — the allowlisted dir. */
    private Path sessionDir;

    private SendChannelFileTool tool;

    @BeforeEach
    void setUp() throws Exception {
        when(weixinChannelAdapter.platformId()).thenReturn("weixin");
        attachmentsRoot = Files.createTempDirectory(
                Path.of("target").toAbsolutePath(), "sendfile-attachments-");
        tool = new SendChannelFileTool(conversationRepository, channelConfigService,
                weixinChannelAdapter, objectMapper, attachmentsRoot.toString());
        sessionDir = Files.createDirectories(attachmentsRoot.resolve(SESSION_ID));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        if (attachmentsRoot != null && Files.exists(attachmentsRoot)) {
            try (var walk = Files.walk(attachmentsRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
            }
        }
    }

    private SkillContext ctx(String sessionId) {
        SkillContext c = new SkillContext();
        c.setSessionId(sessionId);
        return c;
    }

    private ChannelConversationEntity conv(String platform, String conversationId) {
        ChannelConversationEntity e = new ChannelConversationEntity();
        e.setPlatform(platform);
        e.setConversationId(conversationId);
        e.setSessionId(SESSION_ID);
        return e;
    }

    private void stubActiveWeixinConversation() {
        when(conversationRepository.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(conv("weixin", "wx-user-1")));
        when(channelConfigService.getDecryptedConfig("weixin")).thenReturn(Optional.of(
                new ChannelConfigDecrypted(1L, "weixin", null, "{\"bot_token\":\"t\"}", null, null)));
    }

    @Test
    @DisplayName("happy path: reads file under this session's dir, calls adapter, returns sent")
    void happyPath_sends() throws Exception {
        Path file = Files.writeString(sessionDir.resolve("report.pdf"), "PDF-CONTENT");
        when(conversationRepository.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(conv("weixin", "wx-user-1")));
        ChannelConfigDecrypted config = new ChannelConfigDecrypted(
                1L, "weixin", null, "{\"bot_token\":\"t\"}", null, null);
        when(channelConfigService.getDecryptedConfig("weixin")).thenReturn(Optional.of(config));

        SkillResult result = tool.execute(
                Map.of("file_path", file.toString(), "caption", "here you go"), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"status\":\"sent\"");
        assertThat(result.getOutput()).contains("\"kind\":\"file\"");
        verify(weixinChannelAdapter).sendMediaToConversation(
                eq("wx-user-1"), eq(""), any(byte[].class), eq("report.pdf"),
                eq("application/pdf"), eq("here you go"), eq(config));
    }

    @Test
    @DisplayName("image extension routes to kind=image with image mime")
    void imageRoutesToImageKind() throws Exception {
        Path file = Files.writeString(sessionDir.resolve("shot.png"), "PNGDATA");
        stubActiveWeixinConversation();

        SkillResult result = tool.execute(Map.of("file_path", file.toString()), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("\"kind\":\"image\"");
        verify(weixinChannelAdapter).sendMediaToConversation(
                any(), any(), any(), eq("shot.png"), eq("image/png"), any(), any());
    }

    @Test
    @DisplayName("BLOCKER: rejects file path outside the allowlist (path-traversal guard)")
    void rejectsOutOfAllowlistPath() {
        stubActiveWeixinConversation();

        SkillResult result = tool.execute(Map.of("file_path", "/etc/passwd"), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("outside the allowed directories");
        verify(weixinChannelAdapter, never())
                .sendMediaToConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("BLOCKER: rejects ../ traversal escaping the allowlist root")
    void rejectsTraversalEscape() {
        stubActiveWeixinConversation();

        String escape = sessionDir.resolve("../../../../etc/hosts").toString();
        SkillResult result = tool.execute(Map.of("file_path", escape), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("outside the allowed directories");
        verify(weixinChannelAdapter, never())
                .sendMediaToConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("BLOCKER (cross-session exfil): rejects a path under ANOTHER session's attachment dir")
    void rejectsCrossSessionPath() throws Exception {
        // Create a real file under a DIFFERENT session's dir; session-1's agent must not read it.
        Path otherSessionFile = Files.writeString(
                Files.createDirectories(attachmentsRoot.resolve("OTHER-SESSION")).resolve("secret.pdf"),
                "SECRET");
        stubActiveWeixinConversation();

        SkillResult result = tool.execute(
                Map.of("file_path", otherSessionFile.toString()), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("outside the allowed directories");
        verify(weixinChannelAdapter, never())
                .sendMediaToConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("BLOCKER (symlink escape): rejects a symlink inside the session dir pointing outside")
    @DisabledOnOs(OS.WINDOWS) // symlink creation typically requires privilege on Windows
    void rejectsSymlinkEscape() throws Exception {
        // /etc/hosts is a stable, readable file present on Linux + macOS.
        Path target = Path.of("/etc/hosts");
        Path link = sessionDir.resolve("evil-link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception e) {
            // Environment without symlink support: skip rather than fail.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported: " + e.getMessage());
        }
        stubActiveWeixinConversation();

        SkillResult result = tool.execute(Map.of("file_path", link.toString()), ctx(SESSION_ID));

        // toRealPath resolves the symlink to /etc/hosts which is outside the allowlist → rejected.
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("outside the allowed directories");
        verify(weixinChannelAdapter, never())
                .sendMediaToConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("BLOCKER: file exceeding MAX_FILE_BYTES is rejected before send")
    void rejectsOversizedFile() throws Exception {
        // Use a tiny tool instance whose cap we lower via a >cap file. Writing 25MB is slow, so
        // instead create a file just over a small injected cap by constructing the tool normally
        // and writing a file larger than MAX_FILE_BYTES would be too slow — so we assert via a
        // dedicated small-cap seam: write 1 byte over the configured cap using a sparse approach.
        Path big = sessionDir.resolve("huge.bin");
        // Allocate a file exactly MAX_FILE_BYTES + 1 without materializing 25MB in heap.
        try (var ch = Files.newByteChannel(big,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE)) {
            ch.position(SendChannelFileTool.MAX_FILE_BYTES); // seek to cap
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{1})); // write 1 byte → size = cap + 1
        }
        assertThat(Files.size(big)).isEqualTo(SendChannelFileTool.MAX_FILE_BYTES + 1);
        stubActiveWeixinConversation();

        SkillResult result = tool.execute(Map.of("file_path", big.toString()), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("exceeds");
        verify(weixinChannelAdapter, never())
                .sendMediaToConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("temp dir is in the allowlist")
    void allowsTempDir() {
        Path real = tool.resolveWithinAllowlist(
                System.getProperty("java.io.tmpdir"),
                List.of(attachmentsRoot.resolve(SESSION_ID),
                        Path.of(System.getProperty("java.io.tmpdir"))));
        assertThat(real).isNotNull();
    }

    @Test
    @DisplayName("missing file UNDER the session dir passes the guard (fails later at read, not as escape)")
    void missingFileUnderAllowlist_passesGuard() {
        Path missing = sessionDir.resolve("not-created-yet.bin");
        Path resolved = tool.resolveWithinAllowlist(missing.toString(),
                List.of(sessionDir, Path.of(System.getProperty("java.io.tmpdir"))));
        assertThat(resolved).isNotNull();
    }

    @Test
    @DisplayName("non-weixin platform → graceful error (no throw, no adapter call)")
    void nonWeixinPlatform_graceful() {
        when(conversationRepository.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.of(conv("telegram", "tg-user")));

        SkillResult result = tool.execute(Map.of("file_path", "/tmp/whatever"), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not supported on platform 'telegram'");
        verify(weixinChannelAdapter, never())
                .sendMediaToConversation(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("no active channel conversation → graceful error")
    void noConversation_graceful() {
        when(conversationRepository.findBySessionIdAndClosedAtIsNull(SESSION_ID))
                .thenReturn(Optional.empty());

        SkillResult result = tool.execute(Map.of("file_path", "/tmp/x"), ctx(SESSION_ID));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("not in a channel conversation");
    }

    @Test
    @DisplayName("missing file_path → validation error")
    void missingFilePath_validationError() {
        SkillResult result = tool.execute(Map.of(), ctx(SESSION_ID));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("file_path is required");
    }

    @Test
    @DisplayName("missing session id → error")
    void missingSession_error() {
        SkillResult result = tool.execute(Map.of("file_path", "/tmp/x"), ctx(null));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("no active session");
    }

    @Test
    @DisplayName("guessMimeType maps common extensions, defaults to octet-stream")
    void guessMimeType_map() {
        assertThat(SendChannelFileTool.guessMimeType("a.png")).isEqualTo("image/png");
        assertThat(SendChannelFileTool.guessMimeType("a.JPG")).isEqualTo("image/jpeg");
        assertThat(SendChannelFileTool.guessMimeType("a.pdf")).isEqualTo("application/pdf");
        assertThat(SendChannelFileTool.guessMimeType("a.unknownext")).isEqualTo("application/octet-stream");
        assertThat(SendChannelFileTool.guessMimeType("noext")).isEqualTo("application/octet-stream");
    }
}
