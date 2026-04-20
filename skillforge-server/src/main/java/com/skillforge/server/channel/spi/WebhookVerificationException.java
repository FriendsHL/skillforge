package com.skillforge.server.channel.spi;

/** Thrown on signature/token verification failure; controller returns HTTP 401. */
public class WebhookVerificationException extends RuntimeException {
    public WebhookVerificationException(String platform, String reason) {
        super("Webhook verification failed for [" + platform + "]: " + reason);
    }
}
