package com.skillforge.core.context;

public enum PromptTrustLevel {
    TRUSTED_INSTRUCTION,
    TRUSTED_RUNTIME_DATA,
    CONFIGURED_INSTRUCTION,
    USER_DATA,
    STORED_DATA,
    UNTRUSTED_EXTERNAL_DATA,
    MODEL_GENERATED_DATA
}
