package com.skillforge.server.mobile;

import java.util.Set;
import java.util.UUID;

public record MobileDevicePrincipal(UUID deviceId, Long userId, String deviceName, Set<String> scopes) {
}
