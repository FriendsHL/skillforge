package com.skillforge.server.mobile;

import java.time.Instant;
import java.util.UUID;

public record MobilePushTokenResponse(UUID id, String environment, String status, Instant registeredAt) {}
