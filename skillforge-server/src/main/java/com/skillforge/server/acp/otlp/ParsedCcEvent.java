package com.skillforge.server.acp.otlp;

import java.time.Instant;
import java.util.Map;

/**
 * One normalized cc OTLP log/event (ACP-EXTERNAL-AGENT P2-1), produced by
 * {@link OtlpLogsParser} from an OTLP-JSON {@code /v1/logs} payload.
 *
 * <p>{@link #sfSessionId} is the injected {@code sf.session_id} resource attribute
 * (the SkillForge cc sub-session id); it may be {@code null}/blank when an event is
 * not one of ours — such events are dropped by the ingest layer.
 *
 * <p><b>PRIVACY:</b> {@link #attributes} here is the RAW (still possibly
 * PII-bearing) attribute set merged from the logRecord + resource. PII filtering
 * happens in the ingest layer ({@code OtlpIngestService}) right before persistence,
 * NOT here — keep this record a faithful parse so filtering is auditable in one
 * place.
 *
 * @param eventName    e.g. {@code claude_code.api_request} (never null/blank — events
 *                     without a resolvable name are skipped by the parser)
 * @param sfSessionId  injected {@code sf.session_id} resource attr (nullable)
 * @param ccSessionId  cc's own {@code session.id} (nullable, structural)
 * @param eventSeq     {@code event.sequence} (nullable)
 * @param ts           event timestamp (nullable)
 * @param attributes   merged attributes (resource + logRecord), string-valued
 */
public record ParsedCcEvent(
        String eventName,
        String sfSessionId,
        String ccSessionId,
        Long eventSeq,
        Instant ts,
        Map<String, Object> attributes) {
}
