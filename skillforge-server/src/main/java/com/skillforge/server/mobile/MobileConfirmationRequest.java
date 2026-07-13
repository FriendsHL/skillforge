package com.skillforge.server.mobile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MobileConfirmationRequest(String confirmationId, String decision) {
}
