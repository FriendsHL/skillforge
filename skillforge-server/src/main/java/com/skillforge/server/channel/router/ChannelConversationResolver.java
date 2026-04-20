package com.skillforge.server.channel.router;

import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelMessage;
import com.skillforge.server.entity.ChannelConversationEntity;
import com.skillforge.server.entity.UserIdentityMappingEntity;
import com.skillforge.server.repository.ChannelConversationRepository;
import com.skillforge.server.repository.UserIdentityMappingRepository;
import com.skillforge.server.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Extracted from {@link ChannelSessionRouter} so that {@link #resolveSession} and
 * {@link #resolveUser} are invoked through the Spring proxy (not via {@code this.}).
 * <p>
 * If these methods lived on {@code ChannelSessionRouter}, the {@code @Async} entrypoint
 * {@code routeAsync} would call them directly on the unwrapped target and the
 * {@code @Transactional} advice would be silently bypassed — the
 * {@code PESSIMISTIC_WRITE} lock inside {@link ChannelConversationRepository#findActiveForUpdate}
 * would release immediately and the find/create sequence would be non-atomic.
 */
@Service
public class ChannelConversationResolver {

    private static final Logger log = LoggerFactory.getLogger(ChannelConversationResolver.class);

    private final ChannelConversationRepository conversationRepo;
    private final UserIdentityMappingRepository identityRepo;
    private final SessionService sessionService;

    public ChannelConversationResolver(
            ChannelConversationRepository conversationRepo,
            UserIdentityMappingRepository identityRepo,
            SessionService sessionService) {
        this.conversationRepo = conversationRepo;
        this.identityRepo = identityRepo;
        this.sessionService = sessionService;
    }

    /**
     * Find or create an active (platform, conversationId) → sessionId mapping.
     * Uses PESSIMISTIC_WRITE; on rare race the unique index catches it and we retry once.
     */
    @Transactional
    public SessionRouteResult resolveSession(
            ChannelMessage msg,
            ChannelConfigDecrypted config,
            Long mappedUserId) {
        Optional<ChannelConversationEntity> existing =
                conversationRepo.findActiveForUpdate(msg.platform(), msg.conversationId());

        if (existing.isPresent()) {
            String sessionId = existing.get().getSessionId();
            if (sessionService.isChannelSessionActive(sessionId)) {
                Long sessionUserId = sessionService.getSession(sessionId).getUserId();
                if (sessionUserId != null) {
                    return new SessionRouteResult(sessionId, false, sessionUserId);
                }
                Long fallbackUserId = mappedUserId != null ? mappedUserId : SessionService.DEFAULT_CHANNEL_USER_ID;
                return new SessionRouteResult(sessionId, false, fallbackUserId);
            }
            existing.get().setClosedAt(Instant.now());
            conversationRepo.save(existing.get());
        }

        try {
            String newSessionId = sessionService.createChannelSession(
                    config.defaultAgentId(), buildSessionTitle(msg), mappedUserId);
            ChannelConversationEntity newConv = new ChannelConversationEntity();
            newConv.setPlatform(msg.platform());
            newConv.setConversationId(msg.conversationId());
            newConv.setSessionId(newSessionId);
            newConv.setChannelConfigId(config.id());
            conversationRepo.save(newConv);
            Long sessionUserId = sessionService.getSession(newSessionId).getUserId();
            Long effectiveUserId = sessionUserId != null
                    ? sessionUserId
                    : (mappedUserId != null ? mappedUserId : SessionService.DEFAULT_CHANNEL_USER_ID);
            return new SessionRouteResult(newSessionId, true, effectiveUserId);
        } catch (DataIntegrityViolationException race) {
            // uq_ch_conv_active caught a concurrent creator — fall back to the
            // winning row. Won't recurse because it's now visible.
            log.warn("Concurrent conversation creation hit unique constraint, retrying lookup");
            ChannelConversationEntity winner = conversationRepo
                    .findActiveForUpdate(msg.platform(), msg.conversationId())
                    .orElseThrow(() -> race);
            String winnerSessionId = winner.getSessionId();
            Long sessionUserId = sessionService.getSession(winnerSessionId).getUserId();
            Long effectiveUserId = sessionUserId != null
                    ? sessionUserId
                    : (mappedUserId != null ? mappedUserId : SessionService.DEFAULT_CHANNEL_USER_ID);
            return new SessionRouteResult(winnerSessionId, false, effectiveUserId);
        }
    }

    /** Map platform user → SkillForge user; unknown = first-contact, null means anonymous. */
    @Transactional(readOnly = true)
    public Long resolveUser(ChannelMessage msg) {
        return identityRepo.findByPlatformAndPlatformUserId(msg.platform(), msg.platformUserId())
                .map(UserIdentityMappingEntity::getSkillforgeUserId)
                .orElse(null);
    }

    private String buildSessionTitle(ChannelMessage msg) {
        String prefix = "[" + msg.platform() + "] ";
        String body = msg.text();
        if (body == null || body.isBlank()) {
            body = msg.conversationId();
        }
        String trimmed = body.length() > 60 ? body.substring(0, 60) + "…" : body;
        return prefix + trimmed;
    }
}
