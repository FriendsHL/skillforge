package com.skillforge.server.config;

import java.util.Objects;
import java.util.Set;

/** Server-created authority carried by the shared desktop platform token. */
public record PlatformAccessPrincipal(
        Authority authority,
        long actorId,
        Set<String> permissions) {

    public static final String SESSION_RESOLVE_UNKNOWN_PERMISSION =
            "session:resolve-unknown";

    public PlatformAccessPrincipal {
        Objects.requireNonNull(authority, "authority");
        if (actorId < 0L) throw new IllegalArgumentException("actorId must be nonnegative");
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        if (permissions.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("permissions must contain only nonblank values");
        }
        if (authority != Authority.PLATFORM_ADMIN && !permissions.isEmpty()) {
            throw new IllegalArgumentException(
                    "only an administrator principal may carry explicit permissions");
        }
    }

    public PlatformAccessPrincipal(Authority authority) {
        this(authority, 0L, Set.of());
    }

    public PlatformAccessPrincipal(Authority authority, long actorId) {
        this(authority, actorId, Set.of());
    }

    public enum Authority {
        AUTHENTICATED_USER,
        PLATFORM_ADMIN
    }

    public static PlatformAccessPrincipal platformAdmin() {
        return platformAdmin(0L, Set.of(SESSION_RESOLVE_UNKNOWN_PERMISSION));
    }

    public static PlatformAccessPrincipal platformAdmin(
            long actorId, Set<String> permissions) {
        return new PlatformAccessPrincipal(Authority.PLATFORM_ADMIN, actorId, permissions);
    }

    public static PlatformAccessPrincipal authenticatedUser(long actorId) {
        return new PlatformAccessPrincipal(Authority.AUTHENTICATED_USER, actorId, Set.of());
    }

    /** Authorities accepted by Session-scoped commands; broad platform role is not implicit. */
    public Set<String> explicitSessionPermissions() {
        return authority == Authority.PLATFORM_ADMIN ? permissions : Set.of();
    }
}
