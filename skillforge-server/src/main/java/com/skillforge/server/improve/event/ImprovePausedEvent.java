package com.skillforge.server.improve.event;

public record ImprovePausedEvent(String agentId, int abDeclineCount, Long triggeredByUserId) {
}
