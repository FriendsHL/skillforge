package com.skillforge.server.session;

import java.util.Objects;
import java.util.Set;

/** Trusted actor identity supplied by the authenticated server boundary, never request JSON. */
public record UnknownOutcomeResolutionActor(long actorId, Set<String> authorities) {

    public UnknownOutcomeResolutionActor {
        if (actorId < 0L) throw new IllegalArgumentException("actorId must be nonnegative");
        authorities = Set.copyOf(Objects.requireNonNull(authorities, "authorities"));
        if (authorities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("authorities must contain only nonblank values");
        }
    }

    public static UnknownOutcomeResolutionActor authenticated(
            long actorId, Set<String> authorities) {
        return new UnknownOutcomeResolutionActor(actorId, authorities);
    }
}
