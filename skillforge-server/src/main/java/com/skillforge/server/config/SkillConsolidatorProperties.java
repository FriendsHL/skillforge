package com.skillforge.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SKILL-CURATOR V1 — configuration for the nightly skill curator
 * ({@link com.skillforge.server.skill.curate.SkillConsolidator}) that archives
 * non-system, old, rarely-used skills so they stop polluting the agent's
 * tool-selection surface.
 *
 * <pre>
 * skillforge:
 *   skill:
 *     consolidation:
 *       enabled: true                  # master switch for consolidate()
 *       dry-run: true                  # v1 DEFAULT — log only, never mutate
 *       cooldown-days: 30              # skill must be older than this (since createdAt)
 *       min-usage: 1                   # archive only if usageCount < this (==0 by default)
 *       recent-update-grace-days: 7    # skip skills updated within this window
 *       scheduled-enabled: true        # cron gate (read via @Value in the scheduler)
 * </pre>
 *
 * <p>Setters null-coerce nothing (all primitives), mirroring MemoryProperties'
 * defensive style for nested objects.
 */
@ConfigurationProperties(prefix = "skillforge.skill.consolidation")
public class SkillConsolidatorProperties {

    /** Master switch — when false, {@code consolidate()} is a logged no-op. */
    private boolean enabled = true;

    /**
     * v1 DEFAULT = true. When true the curator only logs what it <em>would</em>
     * archive; it never mutates a skill row. Flip to false (per-environment) to
     * let the curator actually disable + archive low-usage skills.
     */
    private boolean dryRun = true;

    /** A skill is a candidate only if its createdAt is older than this many days. */
    private int cooldownDays = 30;

    /** Archive only if {@code usageCount < minUsage} (default 1 ⇒ usageCount==0). */
    private long minUsage = 1;

    /**
     * Mirrors the {@code skillforge.skill.consolidation.scheduled-enabled} property
     * read directly by {@link com.skillforge.server.skill.curate.SkillConsolidationScheduler}
     * via {@code @Value}. Held here so the property bag stays binding-safe (otherwise
     * Spring warns about an unknown {@code scheduled-enabled} key).
     */
    private boolean scheduledEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getCooldownDays() {
        return cooldownDays;
    }

    public void setCooldownDays(int cooldownDays) {
        this.cooldownDays = cooldownDays;
    }

    public long getMinUsage() {
        return minUsage;
    }

    public void setMinUsage(long minUsage) {
        this.minUsage = minUsage;
    }

    public boolean isScheduledEnabled() {
        return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean scheduledEnabled) {
        this.scheduledEnabled = scheduledEnabled;
    }
}
