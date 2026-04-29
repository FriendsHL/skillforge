package com.skillforge.observability.domain;

/** Origin of an LLM span row. */
public enum LlmSpanSource {
    /** Captured by {@code TraceLlmCallObserver} from a real chat call. */
    LIVE,
    /** Migrated from existing {@code t_trace_span} LLM_CALL rows by ETL. */
    LEGACY;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static LlmSpanSource fromWire(String wire) {
        if (wire == null) return LIVE;
        return switch (wire.toLowerCase()) {
            case "live" -> LIVE;
            case "legacy" -> LEGACY;
            default -> throw new IllegalArgumentException("Unknown LlmSpanSource: " + wire);
        };
    }
}
