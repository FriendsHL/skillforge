package com.skillforge.server.mobile;

public interface ApnsClient {
    boolean isConfigured();
    ApnsResult send(String deviceToken, String environment, String payload);
    record ApnsResult(boolean delivered, boolean permanentFailure, String apnsId, String reason) {}
}
