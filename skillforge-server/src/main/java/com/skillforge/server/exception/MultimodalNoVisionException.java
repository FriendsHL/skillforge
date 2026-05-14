package com.skillforge.server.exception;

/**
 * MULTIMODAL-MVP Task #4: thrown when a turn carries multimodal content blocks
 * but the resolved effective model (from
 * {@code agent.multimodalModelId} / {@code session.runtimeModelOverride} /
 * {@code agent.modelId}) is not in any LLM provider's {@code visionModels}
 * allowlist (see {@link com.skillforge.server.config.LlmProperties#supportsVision}).
 *
 * <p>Per PRD Ratify #9: the BE must surface this as an explicit error rather
 * than silently dropping image blocks or falling back to the agent's primary
 * model. ChatService catches this and writes the message + WS error so the
 * frontend can prompt the user to switch the multimodal model in agent config.</p>
 *
 * <p>Error code (stable wire identifier): {@code MULTIMODAL_MODEL_NO_VISION_CAPABILITY}.</p>
 */
public class MultimodalNoVisionException extends RuntimeException {

    public static final String CODE = "MULTIMODAL_MODEL_NO_VISION_CAPABILITY";

    private final String modelId;

    public MultimodalNoVisionException(String modelId) {
        super(CODE + ": model `" + modelId + "` does not support vision input");
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }
}
