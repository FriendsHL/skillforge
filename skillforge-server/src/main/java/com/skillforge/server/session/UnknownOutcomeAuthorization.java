package com.skillforge.server.session;

import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.session.UnknownOutcomeResolutionAck.ActorAuthority;

import java.util.Objects;

/** Shared Session-scoped authorization for unknown-outcome discovery and resolution. */
final class UnknownOutcomeAuthorization {

    private UnknownOutcomeAuthorization() {
    }

    static ActorAuthority authorize(
            SessionEntity session, UnknownOutcomeResolutionActor actor) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(actor, "actor");
        if (Objects.equals(session.getUserId(), actor.actorId())) {
            return ActorAuthority.OWNER;
        }
        if (actor.authorities().contains(UnknownOutcomeResolutionService.ADMIN_AUTHORITY)) {
            return ActorAuthority.ADMIN;
        }
        throw unavailable();
    }

    private static UnknownOutcomeResolutionException unavailable() {
        return new UnknownOutcomeResolutionException(
                UnknownOutcomeResolutionException.Code.RESOLUTION_NOT_AVAILABLE,
                "Unknown outcome resolution is not available");
    }
}
