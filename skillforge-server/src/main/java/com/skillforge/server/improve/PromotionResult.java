package com.skillforge.server.improve;

public record PromotionResult(String status, String reason) {

    public static PromotionResult promoted(String versionId) {
        return new PromotionResult("promoted", "Version " + versionId + " promoted to active");
    }

    public static PromotionResult rejected(String reason) {
        return new PromotionResult("rejected", reason);
    }
}
