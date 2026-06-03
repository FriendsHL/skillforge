package com.skillforge.server.evolve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.observability.entity.LlmSpanEntity;
import com.skillforge.observability.repository.LlmSpanRepository;
import com.skillforge.server.entity.EvalScenarioEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.EvalScenarioDraftRepository;
import com.skillforge.server.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * AUTOEVOLVE-CLOSE-LOOP Phase BC-M1 — rebuilds a real failed session into an
 * isolated, reproducible eval scenario by reading (only) the session's
 * {@code t_llm_span} trace. Nothing outside the DB is touched — in particular
 * the real repo files referenced by the original session are never read or
 * written; we reconstruct fixture content purely from the span trace.
 *
 * <p>Scope (BC-M1): Edit-stale class only. Given a failed {@code Edit} span,
 * we take the file it targeted, find the most recent prior successful
 * Read/Write/Edit on that same path in the session, and use that content as a
 * sandbox fixture. The scenario's behavioral oracle ({@code tool_error_absence})
 * replays the same failure signature so a baseline run reproduces the failure.
 *
 * <p>The produced scenario is saved as {@code status=draft} — a human must
 * activate it before it enters A/B (Iron-Law human gate). The harvest logic is
 * deliberately generic: it describes the mechanism ("reproduce the failure
 * signature"), never a fix.
 */
@Service
public class BadCaseHarvestService {

    private static final Logger log = LoggerFactory.getLogger(BadCaseHarvestService.class);

    /** Tools whose prior successful invocation can supply fixture file content. */
    private static final Set<String> CONTENT_TOOLS = Set.of("Read", "Write", "Edit");

    /** Tool name whose failure we harvest in BC-M1. */
    private static final String EDIT_TOOL = "Edit";

    /**
     * Known stable Edit error fragments. We persist the matching fragment as the
     * oracle's {@code errorSignature} so the replay matches the exact failure
     * mode without coupling to the full (path-bearing) error text.
     */
    private static final List<String> KNOWN_EDIT_SIGNATURES = List.of(
            "old_string not found",
            "old_string is not unique",
            "File does not exist");

    /** Eval sandbox task-path prefix; runSingleScenario rewrites this to the sandbox root. */
    private static final String EVAL_TASK_PREFIX = "/tmp/eval/";

    private final LlmSpanRepository spanRepository;
    private final SessionRepository sessionRepository;
    private final EvalScenarioDraftRepository scenarioRepository;
    private final ObjectMapper objectMapper;

    /**
     * Absolute repo-root prefix stripped from harvested paths so fixtures become
     * relative and the task references the eval sandbox. Configurable so the
     * harvest is portable across checkouts / CI.
     */
    private final String repoRoot;

    public BadCaseHarvestService(LlmSpanRepository spanRepository,
                                 SessionRepository sessionRepository,
                                 EvalScenarioDraftRepository scenarioRepository,
                                 ObjectMapper objectMapper,
                                 @Value("${skillforge.evolve.harvest.repo-root:/Users/youren/myspace/skillforge/}")
                                 String repoRoot) {
        this.spanRepository = spanRepository;
        this.sessionRepository = sessionRepository;
        this.scenarioRepository = scenarioRepository;
        this.objectMapper = objectMapper;
        // Normalize to a trailing slash so prefix stripping is unambiguous.
        this.repoRoot = (repoRoot != null && !repoRoot.endsWith("/")) ? repoRoot + "/" : repoRoot;
        // Config hygiene: the default is a dev-machine path. If it doesn't resolve
        // to a real directory on this host, harvested fixture paths won't be
        // rebased (absolute paths leak into the scenario). Warn so it's caught at
        // startup rather than surfacing as a malformed scenario later.
        if (this.repoRoot == null || this.repoRoot.isBlank() || !Files.isDirectory(Path.of(this.repoRoot))) {
            log.warn("skillforge.evolve.harvest.repo-root='{}' is not an existing directory on this host; "
                    + "harvested fixtures may keep un-rebased absolute paths. Set it to your checkout root.",
                    this.repoRoot);
        }
    }

    /**
     * Rebuild a session's failed {@code Edit} span into a draft eval scenario.
     *
     * @param sessionId     the source session id
     * @param failingSpanId the {@code t_llm_span.span_id} of the failed Edit
     * @return the persisted draft scenario, or empty when the case cannot be
     *         rebuilt (span not found / not an Edit failure / no recoverable
     *         prior file content / no first user prompt)
     */
    @Transactional
    public Optional<EvalScenarioEntity> harvestEditStaleCase(String sessionId, String failingSpanId) {
        if (sessionId == null || sessionId.isBlank() || failingSpanId == null || failingSpanId.isBlank()) {
            log.warn("harvestEditStaleCase: blank sessionId/failingSpanId — skipping");
            return Optional.empty();
        }

        List<LlmSpanEntity> spans = spanRepository.findBySessionIdOrderByStartedAtAsc(sessionId);
        if (spans == null || spans.isEmpty()) {
            log.warn("harvestEditStaleCase: no spans for session {} — skipping", sessionId);
            return Optional.empty();
        }

        int failingIndex = -1;
        LlmSpanEntity failing = null;
        for (int i = 0; i < spans.size(); i++) {
            if (failingSpanId.equals(spans.get(i).getSpanId())) {
                failingIndex = i;
                failing = spans.get(i);
                break;
            }
        }
        if (failing == null) {
            log.warn("harvestEditStaleCase: failing span {} not in session {} — skipping",
                    failingSpanId, sessionId);
            return Optional.empty();
        }
        if (!"tool".equals(failing.getKind()) || !EDIT_TOOL.equals(failing.getName())
                || failing.getError() == null || failing.getError().isBlank()) {
            log.warn("harvestEditStaleCase: span {} is not a failed Edit tool span (kind={}, name={}, hasError={}) — skipping",
                    failingSpanId, failing.getKind(), failing.getName(), failing.getError() != null);
            return Optional.empty();
        }

        String absPath = extractInputField(failing.getInputSummary(), "file_path");
        if (absPath == null || absPath.isBlank()) {
            log.warn("harvestEditStaleCase: failed Edit span {} has no file_path — skipping", failingSpanId);
            return Optional.empty();
        }

        // Latest prior successful Read/Write/Edit on the same path wins.
        String priorContent = null;
        for (int i = 0; i < failingIndex; i++) {
            LlmSpanEntity span = spans.get(i);
            if (!"tool".equals(span.getKind())) continue;
            if (span.getError() != null) continue; // only successful spans
            String name = span.getName();
            if (name == null || !CONTENT_TOOLS.contains(name)) continue;
            String spanPath = extractInputField(span.getInputSummary(), "file_path");
            if (!absPath.equals(spanPath)) continue;
            String content = extractContentForTool(span);
            if (content != null) {
                priorContent = content;
            }
        }
        if (priorContent == null) {
            log.info("harvestEditStaleCase: no recoverable prior content for path {} in session {} — skipping",
                    absPath, sessionId);
            return Optional.empty();
        }

        SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("harvestEditStaleCase: session {} not found — skipping", sessionId);
            return Optional.empty();
        }
        String firstUserPrompt = extractFirstUserPrompt(session.getMessagesJson());
        if (firstUserPrompt == null || firstUserPrompt.isBlank()) {
            log.warn("harvestEditStaleCase: session {} has no first user prompt — skipping", sessionId);
            return Optional.empty();
        }

        String relativePath = rebaseToRelative(absPath);
        String rebasedTask = firstUserPrompt.replace(repoRoot, EVAL_TASK_PREFIX);
        String errorSignature = deriveSignature(failing.getError());
        String oracleExpected = buildOracleExpected(errorSignature);
        if (oracleExpected == null) {
            log.warn("harvestEditStaleCase: failed to encode oracle JSON for session {} — skipping", sessionId);
            return Optional.empty();
        }

        EvalScenarioEntity scenario = new EvalScenarioEntity();
        scenario.setId(UUID.randomUUID().toString());
        scenario.setAgentId(session.getAgentId() != null ? String.valueOf(session.getAgentId()) : null);
        String sidShort = sessionId.substring(0, Math.min(8, sessionId.length()));
        scenario.setName("badcase-edit-" + sidShort);
        scenario.setDescription("Harvested from a failed Edit in session " + sessionId
                + "; replays the tool error signature in an isolated sandbox.");
        scenario.setCategory("session_derived");
        scenario.setSplit("held_out");
        scenario.setTask(rebasedTask);
        scenario.setOracleType("tool_error_absence");
        scenario.setOracleExpected(oracleExpected);
        scenario.setStatus("draft");
        scenario.setSourceType(EvalScenarioEntity.SOURCE_TYPE_SESSION_DERIVED);
        scenario.setPurpose(EvalScenarioEntity.PURPOSE_REGRESSION);
        scenario.setSourceRef("session:" + sessionId);
        scenario.setSourceSessionId(sessionId);
        scenario.setExtractionRationale("Reconstructed fixture from the most recent prior successful "
                + "file access on the target path; oracle reproduces the failure signature.");
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put(relativePath, priorContent);
        scenario.setFixtureFiles(fixtures);

        EvalScenarioEntity saved = scenarioRepository.save(scenario);
        log.info("harvestEditStaleCase: saved draft scenario {} (agent={}, path={}, signature='{}') from session {}",
                saved.getId(), saved.getAgentId(), relativePath, errorSignature, sessionId);
        return Optional.of(saved);
    }

    /** Parse a span's {@code input_summary} JSON and return one string field, or null. */
    private String extractInputField(String inputSummary, String field) {
        if (inputSummary == null || inputSummary.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(inputSummary);
            JsonNode value = node.path(field);
            return value.isMissingNode() || value.isNull() ? null : value.asText(null);
        } catch (Exception e) {
            log.debug("extractInputField: input_summary not parseable JSON ({}): {}", field, e.getMessage());
            return null;
        }
    }

    /**
     * Recover the file content a successful content-tool span left behind:
     * Read → output_summary (the content it returned), Write → content arg,
     * Edit → new_string arg (post-edit text).
     *
     * <p><b>Limitations (per-spec, best-effort):</b>
     * <ul>
     *   <li><b>Edit branch uses new_string</b> — that is only the REPLACED text,
     *       not the full post-edit file. Faithful only when the prior content
     *       source for the path is a Read/Write; the Read→failed-Edit main path
     *       (BC-M1 scope) captures the full content via Read.output_summary.</li>
     *   <li><b>output_summary truncation</b> — span summaries are capped at the
     *       column limit, so source content beyond the cap is silently truncated,
     *       yielding a partial fixture. The current span pool shows no truncation
     *       in practice; M2 will detect a truncation marker and skip such spans
     *       once the marker format is confirmed.</li>
     * </ul>
     */
    private String extractContentForTool(LlmSpanEntity span) {
        String name = span.getName();
        if ("Read".equals(name)) {
            return span.getOutputSummary();
        }
        if ("Write".equals(name)) {
            return extractInputField(span.getInputSummary(), "content");
        }
        if (EDIT_TOOL.equals(name)) {
            return extractInputField(span.getInputSummary(), "new_string");
        }
        return null;
    }

    /** Strip the repo-root prefix (and any leading slashes) so the path is sandbox-relative. */
    private String rebaseToRelative(String absPath) {
        String rel = absPath;
        if (repoRoot != null && !repoRoot.isBlank() && rel.startsWith(repoRoot)) {
            rel = rel.substring(repoRoot.length());
        }
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        return rel;
    }

    /** Map a raw Edit error to a stable signature fragment (no fix description). */
    private String deriveSignature(String rawError) {
        for (String fragment : KNOWN_EDIT_SIGNATURES) {
            if (rawError.contains(fragment)) {
                return fragment;
            }
        }
        // Fallback: first line, capped — keeps the signature stable and short.
        String firstLine = rawError.split("\\R", 2)[0].trim();
        return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
    }

    private String buildOracleExpected(String errorSignature) {
        Map<String, String> oracle = new LinkedHashMap<>();
        oracle.put("tool", EDIT_TOOL);
        oracle.put("errorSignature", errorSignature);
        oracle.put("passWhen", "no_match");
        try {
            return objectMapper.writeValueAsString(oracle);
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort first-user-message extraction (mirrors SkillCreatorService). */
    private String extractFirstUserPrompt(String messagesJson) {
        if (messagesJson == null || messagesJson.isBlank()) return null;
        try {
            List<Map<String, Object>> messages = objectMapper.readValue(
                    messagesJson, new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> msg : messages) {
                if (!"user".equals(msg.get("role"))) continue;
                Object content = msg.get("content");
                if (content instanceof String s && !s.isBlank()) {
                    return s;
                }
                if (content instanceof List<?> blocks) {
                    for (Object block : blocks) {
                        if (block instanceof Map<?, ?> bm && "text".equals(bm.get("type"))) {
                            Object text = bm.get("text");
                            if (text instanceof String ts && !ts.isBlank()) {
                                return ts;
                            }
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("extractFirstUserPrompt: messages_json not parseable: {}", e.getMessage());
            return null;
        }
    }
}
