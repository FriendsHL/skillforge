package com.skillforge.server.acp;

import java.util.Map;

/**
 * Per-run factory for {@link AcpClient} instances (ACP-EXTERNAL-AGENT P1a-2).
 *
 * <p>Each cc run spawns its OWN adapter process, so {@link AcpAgentRunner}
 * constructs a fresh client per call rather than sharing a singleton. This
 * interface is the SEAM that makes the runner unit-testable WITHOUT a real
 * subprocess: production wires {@link ProcessAcpClientFactory} (spawns cc via
 * {@link ProcessAcpTransport}); tests inject a factory backed by an in-memory
 * fake transport.
 *
 * <p>The returned client is NOT started — the runner calls {@link AcpClient#start()}
 * after registering its listeners.
 */
@FunctionalInterface
public interface AcpClientFactory {

    /**
     * Create a fresh, unstarted {@link AcpClient} for one ACP agent run.
     *
     * @param adapterPackage the ACP adapter npm package to launch (per-agent: cc vs
     *                       codex vs …) — resolved by the runner from the agent's
     *                       {@code acp:<key>} model id
     * @param cwd            working directory for the adapter child process (a per-run
     *                       safe dir — never the SkillForge repo root unless worktree)
     * @param extraEnv       extra env vars to set on the child process (may be empty)
     * @return an unstarted client
     */
    AcpClient create(String adapterPackage, String cwd, Map<String, String> extraEnv);
}
