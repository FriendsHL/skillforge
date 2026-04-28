package com.skillforge.server.skill;

import com.skillforge.server.entity.SkillDraftEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Plan r2 §4 — Stage B template renderer for approveDraft pipeline.
 * <p>Takes draft metadata (name / description / triggers / required_tools / promptHint) and
 * writes a SKILL.md file to {@code targetDir}. Does NOT call the LLM (avoids re-entering the
 * agent loop from inside approveDraft).
 * <p>The output schema mirrors what {@link com.skillforge.core.skill.SkillPackageLoader}
 * expects to parse:
 * <pre>
 *   ---
 *   name: ...
 *   description: ...
 *   allowed-tools: ...
 *   ---
 *   (body — promptHint or fallback)
 * </pre>
 */
@Service
public class SkillCreatorService {

    private static final Logger log = LoggerFactory.getLogger(SkillCreatorService.class);

    /**
     * Render the draft into a SKILL.md inside {@code targetDir}. Creates {@code targetDir}
     * (and parents) if missing.
     *
     * @param draft     draft metadata
     * @param targetDir directory to write SKILL.md into
     * @throws IOException on filesystem failures (caller is expected to catch + rethrow per
     *                     plan §3 STEP 3+4).
     */
    public void render(SkillDraftEntity draft, Path targetDir) throws IOException {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(targetDir, "targetDir");

        Files.createDirectories(targetDir);

        String name = safe(draft.getName(), "unnamed-skill");
        String description = safe(draft.getDescription(), "");
        String requiredTools = safe(draft.getRequiredTools(), "");
        String triggers = safe(draft.getTriggers(), "");
        String body = safe(draft.getPromptHint(), "");

        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("name: ").append(escapeYamlScalar(name)).append('\n');
        md.append("description: ").append(escapeYamlScalar(description)).append('\n');
        if (!requiredTools.isBlank()) {
            // SkillDefinition.allowedTools is a List<String>; emit YAML flow-sequence syntax
            // so SkillPackageLoader can parse it back. Accept either ',' or whitespace as
            // input separator (LLM extractor output is inconsistent).
            String[] parts = requiredTools.split("[,\\s]+");
            StringBuilder flow = new StringBuilder("[");
            boolean first = true;
            for (String p : parts) {
                if (p == null || p.isBlank()) continue;
                if (!first) flow.append(", ");
                flow.append(escapeYamlScalar(p.trim()));
                first = false;
            }
            flow.append("]");
            if (flow.length() > 2) {  // not just "[]"
                md.append("allowed-tools: ").append(flow).append('\n');
            }
        }
        if (!triggers.isBlank()) {
            // Triggers in SkillDefinition is also a List<String>.
            String[] parts = triggers.split("[,]+");
            StringBuilder flow = new StringBuilder("[");
            boolean first = true;
            for (String p : parts) {
                if (p == null || p.isBlank()) continue;
                if (!first) flow.append(", ");
                flow.append(escapeYamlScalar(p.trim()));
                first = false;
            }
            flow.append("]");
            if (flow.length() > 2) {
                md.append("triggers: ").append(flow).append('\n');
            }
        }
        md.append("---\n\n");
        md.append("# ").append(name).append("\n\n");
        if (!description.isBlank()) {
            md.append(description).append("\n\n");
        }
        if (!body.isBlank()) {
            md.append(body).append('\n');
        } else {
            md.append("_(promptHint not provided by extractor; populate manually before use)_\n");
        }

        Path skillMd = targetDir.resolve("SKILL.md");
        Files.writeString(skillMd, md.toString(), StandardCharsets.UTF_8);
        log.debug("SkillCreatorService rendered SKILL.md at {}", skillMd);
    }

    private static String safe(String s, String fallback) {
        return s == null ? fallback : s;
    }

    /**
     * Minimal YAML scalar escape — quote if the value contains a colon or starts with a
     * special character. We don't need full YAML coverage because draft fields are short
     * single-line strings.
     */
    private static String escapeYamlScalar(String s) {
        if (s == null) return "";
        boolean needQuote = s.contains(":") || s.contains("#") || s.startsWith("-")
                || s.startsWith("[") || s.startsWith("{") || s.startsWith("!")
                || s.startsWith("*") || s.startsWith("&") || s.startsWith("?")
                || s.startsWith("|") || s.startsWith(">") || s.contains("\n");
        if (!needQuote) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
