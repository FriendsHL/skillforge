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
     * Uses PESSIMISTIC_WRITE to serialize concurrent "none exists → create" races.
     * On unique constraint violation, the exception propagates so the caller can
     * retry in a fresh transaction (retrying in the same poisoned Hibernate session
     * would throw HibernateAssertionFailure).
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
            conversationRepo.closeById(existing.get().getId(), Instant.now());
        }

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
