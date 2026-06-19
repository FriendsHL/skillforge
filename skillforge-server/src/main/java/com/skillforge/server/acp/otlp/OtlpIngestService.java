package com.skillforge.server.acp.otlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.AcpCcEventEntity;
import com.skillforge.server.repository.AcpCcEventRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Binds + persists cc OTLP log/events to a SkillForge cc sub-session
 * (ACP-EXTERNAL-AGENT P2-1).
 *
 * <p>Pipeline per {@code POST /v1/logs} batch:
 * <ol>
 *   <li>{@link OtlpLogsParser} parses the OTLP-JSON tree into {@link ParsedCcEvent}s;</li>
 *   <li>events whose {@code sf.session_id} is absent or does NOT match an existing
 *       {@code t_session} row are DROPPED (the key abuse gate: the receiver is
 *       unauthenticated, so this prevents arbitrary data growing the table);</li>
 *   <li>PII is stripped from the attribute map (email / account uuids / prompt full
 *       text — only {@code prompt_length} is kept); structural attrs are serialized
 *       to {@code attrs_json};</li>
 *   <li>the row is persisted via {@link AcpCcEventRepository}.</li>
 * </ol>
 *
 * <p>The receiver hands work to {@link #ingestAsync(JsonNode)} which offloads to a
 * bounded executor so the cc child's export request returns immediately (the cc hot
 * path is never blocked by DB work).
 */
public class OtlpIngestService {

    private static final Logger log = LoggerFactory.getLogger(OtlpIngestService.class);

    /**
     * Attribute keys that carry PII / raw content — NEVER persisted (P4 governance).
     * {@code prompt} (full user prompt text) is dropped here; {@code prompt_length}
     * is kept (it is NOT in this set) so structure survives without the content.
     */
    private static final Set<String> PII_KEYS = Set.of(
            "user.email",
            "user.account_uuid",
            "user.account_id",
            "user.id",
            "organization.id",
            "prompt");

    /**
     * Per-session row cap. The FK + existence gate already bound growth to real
     * sessions, but a single runaway cc run should not write unbounded rows either;
     * once a session crosses this many events we stop persisting more for it (the
     * run is still functional — only telemetry is shed).
     */
    private static final long MAX_EVENTS_PER_SESSION = 10_000;

    /** Resource attr key carrying the owning SkillForge agent id (injected by AcpAgentRunner). */
    private static final String SF_AGENT_ID = "sf.agent_id";

    private final OtlpLogsParser parser;
    private final AcpCcEventRepository eventRepository;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final Executor ingestExecutor;
    /**
     * P2-2: projects each persisted, PII-filtered event into a span on the cc
     * sub-session's trace. Nullable so the P2-1 unit tests (which assert ONLY the raw
     * row persistence + PII filtering) can construct the service without a trace store;
     * the Spring bean always wires it. A null translator simply skips projection.
     */
    private final CcEventSpanTranslator spanTranslator;

    public OtlpIngestService(OtlpLogsParser parser,
                             AcpCcEventRepository eventRepository,
                             SessionRepository sessionRepository,
                             ObjectMapper objectMapper,
                             Executor ingestExecutor) {
        this(parser, eventRepository, sessionRepository, objectMapper, ingestExecutor, null);
    }

    public OtlpIngestService(OtlpLogsParser parser,
                             AcpCcEventRepository eventRepository,
                             SessionRepository sessionRepository,
                             ObjectMapper objectMapper,
                             Executor ingestExecutor,
                             CcEventSpanTranslator spanTranslator) {
        this.parser = parser;
        this.eventRepository = eventRepository;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
        this.ingestExecutor = ingestExecutor;
        this.spanTranslator = spanTranslator;
    }

    /**
     * Offload ingest to the bounded executor so the receiver responds immediately.
     * If the pool is saturated (AbortPolicy) the batch is dropped with a warning —
     * telemetry loss is acceptable; blocking the cc export is not.
     */
    public void ingestAsync(JsonNode root) {
        try {
            ingestExecutor.execute(() -> {
                try {
                    ingest(root);
                } catch (RuntimeException e) {
                    log.warn("OTLP ingest failed (batch dropped): {}", e.toString());
                }
            });
        } catch (RuntimeException reject) {
            // RejectedExecutionException (pool saturated) — shed this batch.
            log.warn("OTLP ingest executor saturated; dropping batch");
        }
    }

    /**
     * Synchronous ingest (used directly by tests; called on the executor in prod).
     * Returns the number of events actually persisted.
     *
     * <p>Intentionally NOT {@code @Transactional}: this is invoked from
     * {@link #ingestAsync}'s own lambda (self-invocation bypasses the Spring AOP
     * proxy anyway — footgun #2), and each event is independent telemetry, so a
     * per-{@code save()} implicit transaction is the right granularity — a single
     * malformed row never rolls back a whole batch of good ones.
     */
    public int ingest(JsonNode root) {
        List<ParsedCcEvent> events = parser.parse(root);
        if (events.isEmpty()) {
            return 0;
        }
        int persisted = 0;
        for (ParsedCcEvent ev : events) {
            String sfSessionId = ev.sfSessionId();
            // Abuse gate: only our own, existing sessions.
            if (sfSessionId == null || sfSessionId.isBlank()
                    || !sessionRepository.existsById(sfSessionId)) {
                if (log.isDebugEnabled()) {
                    log.debug("OTLP event dropped (unknown/absent sf.session_id={}): {}",
                            sfSessionId, ev.eventName());
                }
                continue;
            }
            if (eventRepository.countBySessionId(sfSessionId) >= MAX_EVENTS_PER_SESSION) {
                log.warn("OTLP event cap reached for session {} ({}); shedding further events",
                        sfSessionId, MAX_EVENTS_PER_SESSION);
                continue;
            }
            persistEvent(sfSessionId, ev);
            persisted++;
        }
        return persisted;
    }

    private void persistEvent(String sfSessionId, ParsedCcEvent ev) {
        Map<String, Object> structural = filterPii(ev.attributes());

        AcpCcEventEntity row = new AcpCcEventEntity();
        row.setSessionId(sfSessionId);
        row.setCcSessionId(truncate(asString(structural.get(OtlpLogsParser.SESSION_ID)), 128));
        row.setEventName(truncate(ev.eventName(), 64));
        row.setEventSeq(ev.eventSeq());
        row.setTs(ev.ts());
        row.setAgentName(truncate(asString(structural.get("agent.name")), 128));
        row.setToolName(truncate(asString(structural.get("tool_name")), 128));
        row.setToolUseId(truncate(asString(structural.get("tool_use_id")), 128));
        row.setAttrsJson(toJson(structural));
        eventRepository.save(row);

        // P2-2: project the persisted, PII-filtered event onto the cc sub-session's
        // trace. Translation reads ONLY the already-filtered `structural` attrs (never
        // the raw PII-bearing set) and never throws — a span-write failure must not
        // break ingest (the raw row above is the source of truth).
        projectSpan(sfSessionId, structural, ev);
    }

    /**
     * P2-2: best-effort span projection. Builds a PII-filtered {@link ParsedCcEvent}
     * view (so the translator can not re-derive PII) and hands it to the translator,
     * which itself swallows errors; this extra guard catches anything unexpected.
     */
    private void projectSpan(String sfSessionId, Map<String, Object> structural, ParsedCcEvent ev) {
        if (spanTranslator == null) {
            return;
        }
        try {
            Long agentId = asLong(structural.get(SF_AGENT_ID));
            ParsedCcEvent filtered = new ParsedCcEvent(
                    ev.eventName(), ev.sfSessionId(), ev.ccSessionId(),
                    ev.eventSeq(), ev.ts(), structural);
            spanTranslator.translate(sfSessionId, agentId, filtered);
        } catch (RuntimeException e) {
            log.warn("OTLP span projection failed (raw row kept): session={} event={}: {}",
                    sfSessionId, ev.eventName(), e.toString());
        }
    }

    private static Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Strip PII keys (and the full prompt text) from the attribute map. Returns a
     * NEW map (never mutates the parsed event's attributes). {@code sf.*} routing
     * attrs are kept (they are structural identifiers, not PII).
     */
    private Map<String, Object> filterPii(Map<String, Object> attrs) {
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (PII_KEYS.contains(e.getKey())) {
                continue;
            }
            filtered.put(e.getKey(), e.getValue());
        }
        return filtered;
    }

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            // Should not happen for a flat string/number/bool map; fall back so a
            // serialization edge case never loses the whole row.
            log.warn("OTLP attrs_json serialization failed; storing empty object: {}", e.toString());
            return "{}";
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
