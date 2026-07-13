package com.skillforge.server.mobile;

import java.util.List;

public record MobilePairingCreateRequest(String serverName, List<String> endpoints) {
}
