package com.skillforge.server.artifact;

/**
 * A safe, structured validation failure for a forbidden Personal App capability.
 * The message and suggested action are suitable for returning to the authoring model;
 * they must never contain source HTML, local paths, or other user-provided content.
 */
public final class InteractiveArtifactViolationException extends IllegalArgumentException {

    private final String violationCode;
    private final String suggestedAction;

    InteractiveArtifactViolationException(
            String violationCode,
            String message,
            String suggestedAction) {
        super(message);
        this.violationCode = violationCode;
        this.suggestedAction = suggestedAction;
    }

    public String getViolationCode() {
        return violationCode;
    }

    public String getSuggestedAction() {
        return suggestedAction;
    }
}
