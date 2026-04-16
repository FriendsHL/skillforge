package com.skillforge.server.improve;

public class ImprovementIneligibleException extends RuntimeException {

    private final String reason;

    public ImprovementIneligibleException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
