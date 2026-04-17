package com.skillforge.core.engine.hook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed, structured lifecycle hook config attached to an {@link com.skillforge.core.model.AgentDefinition}.
 *
 * <p>Parsed eagerly from the {@code t_agent.lifecycle_hooks} JSON column by server-side
 * {@code AgentService.toAgentDefinition}. The dispatcher consumes this object directly and
 * never parses JSON again.
 *
 * <p>The {@code hooks} map is deserialized with {@code String} keys first (so unknown events do
 * not fail the parse), then normalized to {@link HookEvent} keys via {@link HookEvent#fromWire}.
 * Unknown event names drop silently.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LifecycleHooksConfig {

    /** Schema version. Current: 1. Unknown values are still accepted (ignoreUnknown on fields). */
    private int version = 1;

    /** Per-event hook entries. Internal storage is always {@link EnumMap}-backed and non-null. */
    private Map<HookEvent, List<HookEntry>> hooks = new EnumMap<>(HookEvent.class);

    public LifecycleHooksConfig() {}

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    /** Expose hooks as the wire-format {@code Map<String, List<HookEntry>>} for serialization. */
    @JsonProperty("hooks")
    public Map<String, List<HookEntry>> getHooksJson() {
        Map<String, List<HookEntry>> wire = new LinkedHashMap<>();
        for (Map.Entry<HookEvent, List<HookEntry>> e : hooks.entrySet()) {
            if (e.getKey() == null) continue;
            wire.put(e.getKey().wireName(), e.getValue());
        }
        return wire;
    }

    /**
     * Accept the wire-format map {@code Map<String, List<HookEntry>>}. Unknown event names
     * are silently dropped instead of raising a Jackson error (see
     * {@link HookEvent#fromWire}).
     */
    @JsonProperty("hooks")
    public void setHooksJson(Map<String, List<HookEntry>> wire) {
        Map<HookEvent, List<HookEntry>> parsed = new EnumMap<>(HookEvent.class);
        if (wire != null) {
            for (Map.Entry<String, List<HookEntry>> e : wire.entrySet()) {
                HookEvent ev = HookEvent.fromWire(e.getKey());
                if (ev == null) continue;
                List<HookEntry> entries = e.getValue() != null ? e.getValue() : new ArrayList<>();
                parsed.put(ev, entries);
            }
        }
        this.hooks = parsed;
    }

    /** Programmatic access to the normalized enum-keyed map. Not part of the JSON surface. */
    public Map<HookEvent, List<HookEntry>> getHooks() {
        return hooks;
    }

    public void setHooks(Map<HookEvent, List<HookEntry>> hooks) {
        this.hooks = hooks != null ? hooks : new EnumMap<>(HookEvent.class);
    }

    /** Returns the (possibly empty, never null) list of entries registered for an event. */
    public List<HookEntry> entriesFor(HookEvent event) {
        if (hooks == null || event == null) return List.of();
        List<HookEntry> list = hooks.get(event);
        return list != null ? list : List.of();
    }

    /** Convenience: return an empty config. Useful for fallback when JSON parsing fails. */
    public static LifecycleHooksConfig empty() {
        LifecycleHooksConfig c = new LifecycleHooksConfig();
        c.setHooks(new EnumMap<>(HookEvent.class));
        return c;
    }

    /** Helper for building in tests and defaults. */
    public void putEntries(HookEvent event, List<HookEntry> entries) {
        if (event == null) return;
        if (hooks == null) hooks = new EnumMap<>(HookEvent.class);
        hooks.put(event, entries != null ? new ArrayList<>(entries) : new ArrayList<>());
    }
}
