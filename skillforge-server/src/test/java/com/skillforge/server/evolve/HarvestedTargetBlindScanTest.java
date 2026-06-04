package com.skillforge.server.evolve;

import com.skillforge.server.tool.evolve.ListActiveHarvestedScenariosTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BC-M2b BLIND-TEST guard (held-out integrity): the orchestrator prompt (V144)
 * and the new harvested-target tool description must describe only the generic
 * mechanism (discover / target / measure / replay), and must NOT name any
 * specific remedy. A held-out "how to fix" answer must stay out of every
 * persisted product so the evolve loop discovers it on its own — this test fails
 * CI if a remedy phrase ever leaks into these strings.
 */
class HarvestedTargetBlindScanTest {

    /** Remedy / fix phrasings that must never appear (case-insensitive). */
    private static final List<String> FORBIDDEN = List.of(
            "read before", "read-before", "read first", "read the file",
            "read then edit", "read, then edit",
            "先 read", "先read", "先读", "先行读取",
            "传目录", "换成目录", "改成目录", "传文件",
            "pass a directory", "use a directory", "not a file", "is a directory",
            "directory not a file");

    private static String v144() throws Exception {
        try (InputStream in = HarvestedTargetBlindScanTest.class.getResourceAsStream(
                "/db/migration/V144__evolve_orchestrator_harvested_target.sql")) {
            assertThat(in).as("V144 migration must be on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("V144 orchestrator prompt contains no remedy phrasing")
    void v144_noRemedyLeak() throws Exception {
        String sql = v144().toLowerCase();
        for (String phrase : FORBIDDEN) {
            assertThat(sql)
                    .as("V144 must not contain remedy phrase '%s' (blind-test leak)", phrase)
                    .doesNotContain(phrase.toLowerCase());
        }
        // Sanity: it DID wire the read-only discovery tool + target threading.
        assertThat(sql).contains("listactiveharvestedscenarios");
        assertThat(sql).contains("evalscenarioids");
    }

    @Test
    @DisplayName("ListActiveHarvestedScenarios tool description contains no remedy phrasing")
    void toolDescription_noRemedyLeak() {
        String desc = new ListActiveHarvestedScenariosTool(null, null, null).getDescription().toLowerCase();
        for (String phrase : FORBIDDEN) {
            assertThat(desc)
                    .as("tool description must not contain remedy phrase '%s' (blind-test leak)", phrase)
                    .doesNotContain(phrase.toLowerCase());
        }
    }
}
