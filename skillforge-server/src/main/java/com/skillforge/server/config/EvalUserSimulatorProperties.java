package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * V5 EVAL-DYNAMIC-USER-SIM Phase 1.2: configuration for the UserSimulatorAgent
 * dynamic trial harness. YAML prefix: {@code skillforge.eval.user-simulator}.
 *
 * <p>Configuration knobs:
 * <ul>
 *   <li>{@code personas} — 5 fixed persona strings (ratify-locked). Trial
 *       inputs pick one by string match (defensive: orchestrator accepts
 *       arbitrary persona string if not in this list, just logs warning, so
 *       SDK callers can pass a custom persona for one-off testing).</li>
 *   <li>{@code max-turns} — outer ping-pong loop upper bound. Default 10.</li>
 *   <li>{@code max-tokens} — UserSim's per-call LLM max_tokens override.
 *       Phase 1.0 footgun: mimo-v2.5-pro is a reasoning model — kept ≥4000 so
 *       the thinking budget + tool-call iteration doesn't get truncated.</li>
 *   <li>{@code turn-timeout-ms} — per-engine.run timeout. Default 25s (V4
 *       SkillAbEvalService.runMultiTurnScenario parity).</li>
 *   <li>{@code trial-budget-ms} — total trial budget. Default 90s (V4
 *       parity).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "skillforge.eval.user-simulator")
public class EvalUserSimulatorProperties {

    private List<String> personas = new ArrayList<>();
    private int maxTurns = 10;
    private int maxTokens = 4096;
    private long turnTimeoutMs = 25_000L;
    private long trialBudgetMs = 90_000L;

    public List<String> getPersonas() { return personas; }
    public void setPersonas(List<String> personas) {
        this.personas = personas != null ? personas : new ArrayList<>();
    }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public long getTurnTimeoutMs() { return turnTimeoutMs; }
    public void setTurnTimeoutMs(long turnTimeoutMs) { this.turnTimeoutMs = turnTimeoutMs; }

    public long getTrialBudgetMs() { return trialBudgetMs; }
    public void setTrialBudgetMs(long trialBudgetMs) { this.trialBudgetMs = trialBudgetMs; }
}
