package com.skillforge.server.tool.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import com.skillforge.core.skill.Tool;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.weixin.WeixinChannelAdapter;
import com.skillforge.server.channel.platform.weixin.WeixinIlinkClient;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * WECHAT-CHANNEL slice 2 (Option b): agent-facing tool that sends a local file/image to the user
 * over the channel bound to the current session (currently WeChat). Lets an agent push a screenshot
 * / report into the user's WeChat without going through the shared reply pipeline.
 *
 * <p><b>Security (BLOCKER).</b> The {@code file_path} comes from the LLM and is therefore untrusted.
 * Reads are confined to an allowlist of roots (the chat-attachments storage root + the system temp
 * dir). The resolved real path must be contained under one of those roots — anything else
 * (e.g. {@code /etc/passwd}, {@code ../../}, a symlink escape) is rejected. Containment is checked
 * with {@link Path#toRealPath} (resolves symlinks) so a symlink inside the allowlist that points
 * outside cannot smuggle an arbitrary file out.
 */
public class SendChannelFileTool implements Tool {

    public static final String NAME = "SendChannelFile";

    private static final Logger log = LoggerFactory.getLogger(SendChannelFileTool.class);

    /** Hard cap so the agent cannot push an arbitrarily huge file through the CDN path. */
    static final long MAX_FILE_BYTES = 25L * 1024 * 1024; // 25 MB

    private final ChannelConversationRepository conversationRepository;
    private final ChannelConfigService channelConfigService;
    private final WeixinChannelAdapter weixinChannelAdapter;
    private final ObjectMapper objectMapper;

    /** Chat-attachments storage root; the per-session subdirectory is the session's allowlist. */
    private final Path attachmentsRoot;
    /** System temp dir; the second allowlist root (not session-scoped — caller-produced temp files). */
    private final Path tempRoot;

    public SendChannelFileTool(ChannelConversationRepository conversationRepository,
                               ChannelConfigService channelConfigService,
                               WeixinChannelAdapter weixinChannelAdapter,
                               ObjectMapper objectMapper,
                               String attachmentsRoot) {
        this.conversationRepository = conversationRepository;
        this.channelConfigService = channelConfigService;
        this.weixinChannelAdapter = weixinChannelAdapter;
        this.objectMapper = objectMapper;
        this.attachmentsRoot = Path.of(attachmentsRoot).toAbsolutePath().normalize();
        this.tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
    }

    /**
     * Build the per-session allowlist. SECURITY: the attachments half is scoped to
     * {@code attachmentsRoot/<sessionId>} (ChatAttachmentService stores at
     * {@code attachmentsRoot/{sessionId}/{id+ext}}), so a session-A agent cannot reference a
     * session-B attachment path and exfiltrate it. The temp dir is shared (caller-produced files).
     */
    private List<Path> allowedRootsFor(String sessionId) {
        return List.of(
                attachmentsRoot.resolve(sessionId).toAbsolutePath().normalize(),
                tempRoot);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Send a local file or image to the user over the messaging channel bound to this "
                + "conversation (e.g. WeChat). Use this when the user is talking to you through a "
                + "chat channel and asks you to \"send me this file/screenshot/report on WeChat\", "
                + "or when delivering a generated artifact (a screenshot, chart, PDF, log) is more "
                + "useful as an attachment than as inline text. Images (png/jpg/etc.) are sent as "
                + "inline images; everything else is sent as a file attachment. "
                + "Required: file_path (a path the server already produced — a chat-attachment or a "
                + "temp file). Optional: caption (sent as a short text message before the file). "
                + "Only works inside an active channel conversation; returns an error otherwise.";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_path", Map.of(
                "type", "string",
                "description", "Absolute path to the local file to send. Must be a file the server "
                        + "already produced under its attachments storage or temp directory."
        ));
        properties.put("caption", Map.of(
                "type", "string",
                "description", "Optional caption text sent as a separate message before the file."
        ));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("file_path"));
        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            if (input == null) {
                return SkillResult.validationError("input is required");
            }
            String filePath = asString(input.get("file_path"));
            if (filePath == null || filePath.isBlank()) {
                return SkillResult.validationError("file_path is required");
            }
            String caption = asString(input.get("caption"));

            String sessionId = context == null ? null : context.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return SkillResult.error("SendChannelFile: no active session in context");
            }

            // Resolve the active channel conversation for this session.
            Optional<ChannelConversationEntity> convOpt =
                    conversationRepository.findBySessionIdAndClosedAtIsNull(sessionId);
            if (convOpt.isEmpty()) {
                return SkillResult.error(
                        "SendChannelFile: this session is not in a channel conversation; "
                                + "files can only be sent from a channel-bound chat (e.g. WeChat).");
            }
            ChannelConversationEntity conv = convOpt.get();
            String platform = conv.getPlatform();
            if (!weixinChannelAdapter.platformId().equals(platform)) {
                // Graceful (not an exception) for unsupported platforms.
                return SkillResult.error(
                        "SendChannelFile: file send is not supported on platform '" + platform + "'.");
            }

            // SECURITY: path-containment guard — must run BEFORE touching the file. The allowlist
            // is scoped to THIS session's attachment dir (+ shared temp dir) so cross-session
            // attachment paths are rejected.
            Path resolved;
            try {
                resolved = resolveWithinAllowlist(filePath, allowedRootsFor(sessionId));
            } catch (SecurityException se) {
                log.warn("SendChannelFile rejected out-of-allowlist path for session [{}]", sessionId);
                return SkillResult.error("SendChannelFile: " + se.getMessage());
            }

            byte[] bytes;
            try {
                long size = Files.size(resolved);
                if (size > MAX_FILE_BYTES) {
                    return SkillResult.error("SendChannelFile: file exceeds "
                            + (MAX_FILE_BYTES / (1024 * 1024)) + "MB limit");
                }
                bytes = Files.readAllBytes(resolved);
            } catch (IOException e) {
                return SkillResult.error("SendChannelFile: cannot read file: " + e.getMessage());
            }

            Optional<ChannelConfigDecrypted> config = channelConfigService.getDecryptedConfig(platform);
            if (config.isEmpty()) {
                return SkillResult.error(
                        "SendChannelFile: weixin channel config missing/inactive (re-bind required)");
            }

            // to_user_id == conversationId (the parser sets conversationId = from_user_id).
            String toUserId = conv.getConversationId();
            if (toUserId == null || toUserId.isBlank()) {
                return SkillResult.error("SendChannelFile: cannot resolve recipient (to_user_id)");
            }

            String fileName = resolved.getFileName() == null ? "file" : resolved.getFileName().toString();
            String mimeType = guessMimeType(fileName);

            try {
                // contextToken empty: agent-tool path has no specific inbound message in scope.
                weixinChannelAdapter.sendMediaToConversation(
                        toUserId, "", bytes, fileName, mimeType, caption, config.get());
            } catch (WeixinIlinkClient.WeixinIlinkException e) {
                log.warn("SendChannelFile weixin send failed for session [{}]: {}",
                        sessionId, e.getMessage());
                return SkillResult.error("SendChannelFile: send failed: " + e.getMessage());
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "sent");
            response.put("platform", platform);
            response.put("file_name", fileName);
            response.put("kind", mimeType.startsWith("image/") ? "image" : "file");
            response.put("bytes", bytes.length);
            return SkillResult.success(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.error("SendChannelFile execute failed", e);
            return SkillResult.error("SendChannelFile error: " + e.getMessage());
        }
    }

    /**
     * Resolve {@code filePath} and assert it is contained under one of {@code allowedRoots}.
     *
     * <p>Uses {@link Path#toRealPath} (follows symlinks, requires the file to exist) so that a
     * symlink located inside an allowed root but pointing outside it is also rejected. When the file
     * does not yet exist it falls back to a syntactic {@code .normalize()} of the candidate (the
     * read fails afterwards anyway), but still compares against each root's REAL path when the root
     * directory exists — so the macOS {@code /var → /private/var} symlink does not cause a
     * false-accept/false-reject.
     *
     * @throws SecurityException when the path escapes every allowed root.
     */
    Path resolveWithinAllowlist(String filePath, List<Path> allowedRoots) {
        Path candidate = Path.of(filePath);
        boolean existed = true;
        Path resolved;
        try {
            resolved = candidate.toRealPath();
        } catch (IOException e) {
            // File missing / unreadable: a non-existing path cannot be realpath-resolved, so use the
            // syntactic normalized form. We still realpath each existing root below so containment is
            // checked against the canonical root form.
            existed = false;
            resolved = candidate.toAbsolutePath().normalize();
        }
        for (Path root : allowedRoots) {
            // Realpath the root whenever the root dir exists (both branches), so a symlinked root
            // (e.g. macOS /var → /private/var) compares canonically. Fall back to the
            // absolute+normalized root when the root dir itself does not exist yet.
            Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                realRoot = root;
            }
            if (resolved.startsWith(realRoot)) {
                return resolved;
            }
            // For a MISSING candidate, also accept against the non-realpath'd root form: the
            // candidate was normalized (not realpath'd) so it may carry the /var (not /private/var)
            // prefix even though realRoot is canonicalized.
            if (!existed && resolved.startsWith(root)) {
                return resolved;
            }
        }
        throw new SecurityException(
                "file_path is outside the allowed directories (this session's attachments dir / temp dir)");
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Minimal extension→MIME map covering the common cases (mirrors the reference channel's mime
     * table). Defaults to {@code application/octet-stream} (→ sent as a file attachment).
     */
    static String guessMimeType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot >= 0 ? lower.substring(dot) : "";
        return switch (ext) {
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".pdf" -> "application/pdf";
            case ".txt" -> "text/plain";
            case ".csv" -> "text/csv";
            case ".zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }
}
