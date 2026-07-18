package com.skillforge.server.mobile;

import java.time.Instant;

public record MobilePersonalAppPreferenceResponse(
        String artifactId,
        boolean favorite,
        Instant lastOpenedAt) { }
