package com.skillforge.server.engine;

import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.engine.confirm.ChannelUnavailableException;
import com.skillforge.core.engine.confirm.ConfirmationPrompter;
import com.skillforge.core.engine.confirm.ConfirmationPromptPayload;
import com.skillforge.core.engine.confirm.Decision;
import com.skillforge.core.engine.confirm.PendingConfirmation;
import com.skillforge.core.engine.confirm.PendingConfirmationRegistry;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.platform.feishu.FeishuClient;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.DeliveryResult;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link ConfirmationPrompter} implementation — runs synchronously on the engine
 * main loop thread. Resolves the session's delivery channel (web / feishu / none), pushes
 * the prompt, blocks on a latch, returns a {@link Decision}.
 *
 * <p>Strict encryptKey predicate: if feishu is the only available channel but
 * {@code webhookSecret} (a.k.a. encryptKey) is blank, throws
 * {@link ChannelUnavailableException} instead of sending an unprotected card. Double-guard
 * with {@code FeishuCardActionVerifier.verifyStrict}.
 */
@Component
public class DefaultConfirmationPrompter implements ConfirmationPrompter {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfirmationPrompter.class);

    private final PendingConfirmationRegistry pendingConfirmationRegistry;
    private final ChatEventBroadcaster broadcaster;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ChannelConversationRepository channelConversationRepository;
    private final ChannelConfigService channelConfigService;
    private final FeishuClient feishuClient;

    public DefaultConfirmationPrompter(PendingConfirmationRegistry pendingConfirmationRegistry,
                                       ChatEventBroadcaster broadcaster,
                                       ChatWebSocketHandler chatWebSocketHandler,
                                       ChannelConversationRepository channelConversationRepository,
                                       ChannelConfigService channelConfigService,
                                       FeishuClient feishuClient) {
        this.pendingConfirmationRegistry = pendingConfirmationRegistry;
        this.broadcaster = broadcaster;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.channelConversationRepository = channelConversationRepository;
        this.channelConfigService = channelConfigService;
        this.feishuClient = feishuClient;
    }

    @Override
    public Decision prompt(ConfirmationRequest request) {
        ConfirmationPromptPayload payload = promptNonBlocking(request);
        String sid = request.sessionId();
        String confirmationId = payload.confirmationId();
        try {
            Decision d;
            try {
                d = pendingConfirmationRegistry.await(confirmationId, request.timeoutSeconds());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Decision.DENIED;
            }
            if (d == null) d = Decision.TIMEOUT;
            log.info("Install confirmation decided sid={} tool={} target={} decision={}",
                    sid, request.installTool(), request.installTarget(), d);
            return d;
        } finally {
            pendingConfirmationRegistry.removeIfPresent(confirmationId);
        }
    }

    @Override
    public ConfirmationPromptPayload promptNonBlocking(ConfirmationRequest request) {
        String sid = request.sessionId();
        String toolUseId = request.toolUseId();

        // 1. Resolve channel + triggerer open_id
        Optional<ChannelConversationEntity> convOpt =
                channelConversationRepository.findBySessionIdAndClosedAtIsNull(sid);
        String channel = convOpt.map(ChannelConversationEntity::getPlatform).orElse("web");
        String chatId = convOpt.map(ChannelConversationEntity::getConversationId).orElse(null);
        String triggererOpenId = chatWebSocketHandler != null
                ? chatWebSocketHandler.getCurrentTriggererOpenId(sid)
                : null;

        // 2. Feishu pre-check: encryptKey blank → not a usable channel
        ChannelConfigDecrypted feishuCfg = null;
        if ("feishu".equals(channel)) {
            Optional<ChannelConfigDecrypted> cfgOpt = channelConfigService.getDecryptedConfig("feishu");
            if (cfgOpt.isEmpty()) {
                throw new ChannelUnavailableException(
                        "Confirmation channel unavailable: feishu not configured");
            }
            feishuCfg = cfgOpt.get();
            if (feishuCfg.webhookSecret() == null || feishuCfg.webhookSecret().isBlank()) {
                throw new ChannelUnavailableException(
                        "Confirmation channel unavailable: feishu encryptKey not configured");
            }
        }

        // 3. Register pending confirmation
        String confirmationId = UUID.randomUUID().toString();
        PendingConfirmation pc = new PendingConfirmation(
                confirmationId, sid, toolUseId,
                request.installTool(), request.installTarget(),
                request.command(),
                triggererOpenId,
                request.timeoutSeconds());
        pendingConfirmationRegistry.register(pc);

        ConfirmationPromptPayload payload = buildPayload(pc);

        try {
            // 4. Push via channel
            if ("feishu".equals(channel)) {
                if (chatId == null || chatId.isBlank()) {
                    throw new ChannelUnavailableException(
                            "Confirmation channel unavailable: feishu chatId missing");
                }
                DeliveryResult dr = feishuClient.sendInteractiveAction(chatId, payload, feishuCfg);
                if (dr == null || !dr.success()) {
                    throw new ChannelUnavailableException(
                            "Failed to deliver feishu confirmation card: "
                                    + (dr == null ? "null result" : dr.errorMessage()));
                }
            } else {
                // web: broadcast via WebSocket
                if (broadcaster == null) {
                    throw new ChannelUnavailableException(
                            "Confirmation channel unavailable: WS broadcaster not configured");
                }
                broadcaster.confirmationRequired(sid, payload);
            }
        } catch (RuntimeException e) {
            pendingConfirmationRegistry.removeIfPresent(confirmationId);
            throw e;
        }

        // 5. Broadcast session status → waiting_user (regardless of channel; dashboard listens)
        if (broadcaster != null) {
            broadcaster.sessionStatus(sid, "waiting_user", "waiting_confirmation", null);
        }
        return payload;
    }

    private ConfirmationPromptPayload buildPayload(PendingConfirmation pc) {
        String preview = pc.commandPreview();
        if (preview != null && preview.length() > 240) {
            preview = preview.substring(0, 240) + "…";
        }
        if ("CreateAgent".equals(pc.installTool())) {
            return new ConfirmationPromptPayload(
                    pc.confirmationId(),
                    pc.sessionId(),
                    pc.installTool(),
                    pc.installTarget(),
                    preview,
                    "Create Agent approval",
                    "The agent wants to create a new active Agent named `" + pc.installTarget()
                            + "`. Review the requested configuration before approving.",
                    List.of(
                            new ConfirmationPromptPayload.ConfirmationChoice("approved", "Create Agent", "primary"),
                            new ConfirmationPromptPayload.ConfirmationChoice("denied", "Deny", "danger")),
                    Instant.now().plusSeconds(pc.timeoutSeconds()));
        }
        if ("UpdateAgent".equals(pc.installTool())) {
            return new ConfirmationPromptPayload(
                    pc.confirmationId(),
                    pc.sessionId(),
                    pc.installTool(),
                    pc.installTarget(),
                    preview,
                    "Update Agent approval",
                    "The agent wants to update Agent `" + pc.installTarget()
                            + "`. Agent-authored hook proposals, if any, will remain pending for separate review.",
                    List.of(
                            new ConfirmationPromptPayload.ConfirmationChoice("approved", "Update Agent", "primary"),
                            new ConfirmationPromptPayload.ConfirmationChoice("denied", "Deny", "danger")),
                    Instant.now().plusSeconds(pc.timeoutSeconds()));
        }
        String description;
        if ("*".equals(pc.installTarget()) || "multiple".equals(pc.installTool())
                || "unknown".equals(pc.installTool())) {
            description = "The agent wants to run a command that looks like an install, "
                    + "but we could not parse a single target. This confirmation will NOT be cached; "
                    + "you will be asked again next time.";
        } else {
            description = "The agent wants to run `" + pc.installTool() + " install " + pc.installTarget()
                    + "`. Approving allows sub-agents and team members from this session to install the "
                    + "same target without re-prompting; a different target will prompt again.";
        }
        return new ConfirmationPromptPayload(
                pc.confirmationId(),
                pc.sessionId(),
                pc.installTool(),
                pc.installTarget(),
                preview,
                "Install confirmation",
                description,
                List.of(
                        new ConfirmationPromptPayload.ConfirmationChoice("approved", "Approve", "primary"),
                        new ConfirmationPromptPayload.ConfirmationChoice("denied", "Deny", "danger")),
                Instant.now().plusSeconds(pc.timeoutSeconds()));
    }
}
