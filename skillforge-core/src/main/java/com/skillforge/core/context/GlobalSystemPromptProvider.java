package com.skillforge.core.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the platform-wide global system prompt from the classpath resource
 * {@code prompts/global-system-prompt.md} once at construction time and caches it.
 * <p>
 * This prompt is a built-in, code-owned resource: it is identical for every native
 * agent (main / sub / system / workflow) and every user. It replaces the legacy
 * per-user {@code UserConfigEntity.claudeMd} injection — see {@code EngineConfig}
 * where it is wired into {@code AgentLoopEngine.setClaudeMdProvider}.
 * <p>
 * Loaded as UTF-8 (the prompt is Chinese). The resource is mandatory: if it is
 * missing or blank the constructor fails fast, because silently injecting an empty
 * prompt would degrade every agent without any signal.
 * <p>
 * Thread-safe: the cached value is immutable after construction.
 */
public class GlobalSystemPromptProvider {

    private static final Logger log = LoggerFactory.getLogger(GlobalSystemPromptProvider.class);
    private static final String RESOURCE_PATH = "prompts/global-system-prompt.md";

    private final String prompt;

    public GlobalSystemPromptProvider() {
        this(RESOURCE_PATH);
    }

    /**
     * Test seam: load from an arbitrary classpath resource path so the fail-fast paths
     * (missing / blank resource) are coverable without mocking the classloader.
     */
    GlobalSystemPromptProvider(String resourcePath) {
        this.prompt = loadResource(resourcePath);
        log.info("Loaded global system prompt from classpath '{}' ({} chars)",
                resourcePath, prompt.length());
    }

    /**
     * @return the global system prompt body (never null, never blank).
     */
    public String get() {
        return prompt;
    }

    private static String loadResource(String resourcePath) {
        try (InputStream is = GlobalSystemPromptProvider.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Global system prompt resource not found on classpath: " + resourcePath);
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (content.isBlank()) {
                throw new IllegalStateException(
                        "Global system prompt resource is blank: " + resourcePath);
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read global system prompt resource: " + resourcePath, e);
        }
    }
}
