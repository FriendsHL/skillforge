package com.skillforge.server.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * EVAL-V2 Q2: writes "base" eval scenarios (system-wide test cases not tied
 * to a specific agent) to the home directory at
 * {@code ~/.skillforge/eval-scenarios/<id>.json}. The classpath
 * {@code eval/scenarios/*.json} seeds remain read-only — operator-added /
 * agent-added scenarios live here so they can be edited or removed without
 * rebuilding the jar.
 *
 * <p>{@link ScenarioLoader} reads from both classpath and this directory at
 * startup (and on each {@code loadAll()} call), so a write here is picked up
 * on the next eval run without restart.
 *
 * <p>The same service is used by the {@code POST /eval/scenarios/base}
 * controller endpoint and the {@code AddEvalScenario} agent tool — both
 * paths converge here so validation / file naming stays consistent.
 */
@Service
public class BaseScenarioService {

    private static final Logger log = LoggerFactory.getLogger(BaseScenarioService.class);

    /** Allow only safe id characters; rejects path separators / dots / shell metas. */
    private static final Pattern SAFE_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

    private static final List<String> ALLOWED_ORACLE_TYPES =
            List.of("exact_match", "contains", "regex", "llm_judge");

    private final ObjectMapper objectMapper;
    private final Path homeDir;

    public BaseScenarioService(ObjectMapper objectMapper,
                               @Value("${skillforge.eval.base-scenarios-dir:#{null}}") String configuredDir) {
        // pretty-print so operators can hand-edit the resulting JSON later.
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.homeDir = resolveHomeDir(configuredDir);
        try {
            Files.createDirectories(this.homeDir);
        } catch (IOException e) {
            log.warn("Failed to create base scenarios dir {}: {}", this.homeDir, e.getMessage());
        }
        log.info("BaseScenarioService initialized: dir={}", this.homeDir);
    }

    private static Path resolveHomeDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(expandTilde(configured));
        }
        return Path.of(System.getProperty("user.home"), ".skillforge", "eval-scenarios");
    }

    private static String expandTilde(String raw) {
        if (raw.equals("~")) return System.getProperty("user.home");
        if (raw.startsWith("~/")) return System.getProperty("user.home") + raw.substring(1);
        return raw;
    }

    /** Where this scenario id would land on disk. Public so callers can echo it back. */
    public Path pathFor(String id) {
        return homeDir.resolve(id + ".json");
    }

    /** Where the loader should look for home-dir scenarios. */
    public Path getHomeDir() {
        return homeDir;
    }

    /**
     * Thrown when the caller asks to add a scenario whose id already has a
     * file on disk and {@code force=false}. The controller maps this to
     * HTTP 409 Conflict.
     */
    public static class ScenarioAlreadyExistsException extends RuntimeException {
        private final String id;
        public ScenarioAlreadyExistsException(String id) {
            super("base scenario already exists: id=" + id + " (pass force=true to overwrite)");
            this.id = id;
        }
        public String getId() { return id; }
    }

    /**
     * Backward-compat shim — delegates to {@link #addBaseScenario(Map, boolean)}
     * with {@code force=false} (no overwrite). Existing callers that don't
     * care about overwrite semantics keep working unchanged.
     */
    public String addBaseScenario(Map<String, Object> body) throws IOException {
        return addBaseScenario(body, false);
    }

    /**
     * Writes a base scenario JSON file. Returns the saved id.
     *
     * <p>Validation failures throw {@link IllegalArgumentException} (controller
     * maps to 400). When {@code force=false} and a file with this id already
     * exists in the home dir, throws {@link ScenarioAlreadyExistsException}
     * (controller maps to 409). With {@code force=true}, an existing file is
     * overwritten. Other I/O failures bubble up as {@link IOException}.
     *
     * <p>Path traversal defense: the {@link #SAFE_ID} regex rejects any id
     * containing path separators or shell metacharacters before we compose
     * the target path, so {@code Files.write} can never escape {@link #homeDir}.
     */
    public String addBaseScenario(Map<String, Object> body, boolean force) throws IOException {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("body must not be empty");
        }

        String id = optionalString(body.get("id"));
        if (id == null) {
            // EVAL-V2 Q2 brief: when caller omits id, generate a uuid so the Tool
            // path can persist a scenario without first picking a stable id.
            id = java.util.UUID.randomUUID().toString();
        }
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "id must match [a-zA-Z0-9][a-zA-Z0-9._-]{0,63} (letters/digits/._-, no slashes/dots-only)");
        }

        String name = optionalString(body.get("name"));
        if (name == null) {
            throw new IllegalArgumentException("name is required");
        }
        String task = optionalString(body.get("task"));
        if (task == null) {
            throw new IllegalArgumentException("task is required");
        }

        // Oracle is optional but if present must be a map with a known type.
        Object oracleRaw = body.get("oracle");
        if (oracleRaw != null) {
            if (!(oracleRaw instanceof Map<?, ?> oracleMap)) {
                throw new IllegalArgumentException("oracle must be an object");
            }
            String oracleType = optionalString(oracleMap.get("type"));
            if (oracleType != null && !ALLOWED_ORACLE_TYPES.contains(oracleType.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "oracle.type must be one of: " + String.join(", ", ALLOWED_ORACLE_TYPES));
            }
        }

        // Build a stable LinkedHashMap so the on-disk JSON has predictable
        // field order (id / name / category / split / task / oracle / etc.).
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", id);
        normalized.put("name", name);
        if (body.containsKey("description")) normalized.put("description", body.get("description"));
        normalized.put("category", body.getOrDefault("category", "session_derived"));
        normalized.put("split", body.getOrDefault("split", "held_out"));
        normalized.put("task", task);
        if (body.containsKey("oracle")) normalized.put("oracle", body.get("oracle"));
        if (body.containsKey("setup")) normalized.put("setup", body.get("setup"));
        if (body.containsKey("toolsHint")) normalized.put("toolsHint", body.get("toolsHint"));
        if (body.containsKey("maxLoops")) normalized.put("maxLoops", body.get("maxLoops"));
        if (body.containsKey("performanceThresholdMs"))
            normalized.put("performanceThresholdMs", body.get("performanceThresholdMs"));
        if (body.containsKey("tags")) normalized.put("tags", body.get("tags"));

        Path target = pathFor(id);
        // Defense-in-depth: regex above already rejects traversal characters,
        // but normalize + verify the target is still inside homeDir before we
        // touch the filesystem (catches future regex regressions).
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedHome = homeDir.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedHome)) {
            throw new IllegalArgumentException("invalid id (resolves outside home dir): " + id);
        }
        if (!force && Files.exists(normalizedTarget)) {
            throw new ScenarioAlreadyExistsException(id);
        }
        // Ensure parent dir exists (in case the constructor failed silently).
        Files.createDirectories(normalizedTarget.getParent());

        // Serialize first, then write — writeValue would leave a half-written
        // file if disk fills up mid-stream and the loader reads it before it
        // finishes. Buffer-then-write avoids that race.
        byte[] bytes = objectMapper.writeValueAsBytes(normalized);
        Files.write(normalizedTarget, bytes);
        log.info("Wrote base scenario: id={} path={} force={}", id, normalizedTarget, force);
        return id;
    }

    private static String optionalString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
