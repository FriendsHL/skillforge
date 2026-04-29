package com.skillforge.observability.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Blob 在文件系统中的位置描述。
 *
 * <p>{@code part} ∈ {@code {request, response, sse}}；
 * {@code request}/{@code response} 落 .json，{@code sse} 落 .txt。
 *
 * <p>格式：{@code data/llm-payloads/{yyyyMMdd}/{traceId}/{spanId}-{part}.{ext}}
 */
public record BlobRef(
        String yyyymmdd,
        String traceId,
        String spanId,
        String part
) {
    private static final DateTimeFormatter YMD = DateTimeFormatter
            .ofPattern("yyyyMMdd").withZone(ZoneId.of("UTC"));

    public BlobRef {
        if (yyyymmdd == null || !yyyymmdd.matches("\\d{8}")) {
            throw new IllegalArgumentException("yyyymmdd must be 8 digits, got: " + yyyymmdd);
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId required");
        }
        if (spanId == null || spanId.isBlank()) {
            throw new IllegalArgumentException("spanId required");
        }
        if (part == null
                || !(part.equals("request") || part.equals("response") || part.equals("sse"))) {
            throw new IllegalArgumentException("part must be request|response|sse, got: " + part);
        }
    }

    /** Helper: build a ref dated by {@code at} (UTC day). */
    public static BlobRef of(Instant at, String traceId, String spanId, String part) {
        return new BlobRef(YMD.format(at), traceId, spanId, part);
    }

    /** Relative path from blob store root. */
    public String toRelativePath() {
        String ext = "sse".equals(part) ? "txt" : "json";
        return yyyymmdd + "/" + traceId + "/" + spanId + "-" + part + "." + ext;
    }
}
