package com.skillforge.server.improve.surface;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MULTI-SURFACE-FLYWHEEL V4 Phase 1.1 — central lookup for the three
 * {@link OptimizableSurface} implementations. Spring auto-injects every bean
 * implementing {@code OptimizableSurface<?>} into the constructor list; we
 * dispatch by {@link OptimizableSurface#surfaceType()}.
 *
 * <p>Used by:
 * <ul>
 *   <li>Phase 1.3 {@code AttributionApprovalService.dispatchBehaviorRuleSurface}
 *       — looks up surface by string from {@code t_optimization_event.surface_type}.</li>
 *   <li>Phase 1.2 {@code AbstractAbEvalRunner} subclasses — receive their
 *       surface via constructor injection but the registry stays the
 *       cross-surface lookup point for ad-hoc paths.</li>
 * </ul>
 *
 * <p>Throws {@link IllegalArgumentException} on unknown surface_type rather
 * than returning null — callers should never be passing surface_type values
 * outside the {@code {skill, prompt, behavior_rule}} closed set (V80 schema
 * enforces this via CHECK constraint).
 */
@Component
public class SurfaceRegistry {

    private final Map<String, OptimizableSurface<?>> bySurfaceType;

    public SurfaceRegistry(List<OptimizableSurface<?>> surfaces) {
        Map<String, OptimizableSurface<?>> map = new HashMap<>();
        for (OptimizableSurface<?> s : surfaces) {
            String key = s.surfaceType();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(
                        "OptimizableSurface implementation returned null/blank surfaceType: "
                                + s.getClass().getName());
            }
            OptimizableSurface<?> prior = map.put(key, s);
            if (prior != null) {
                throw new IllegalStateException(
                        "Duplicate OptimizableSurface registration for surfaceType=" + key
                                + ": " + prior.getClass().getName()
                                + " vs " + s.getClass().getName());
            }
        }
        this.bySurfaceType = Collections.unmodifiableMap(map);
    }

    /**
     * Resolve a surface by its discriminator. Caller is responsible for the
     * generic parameter match — passing {@code "skill"} returns a surface
     * whose {@code <V>} is {@code SkillEntity}; misusing the result will
     * surface as a {@link ClassCastException} on the first
     * {@code loadActive} / {@code loadVersion} call. Phase 1.2's
     * {@code AbstractAbEvalRunner} subclasses are typed at the class level
     * so the cast happens once at construction and stays type-safe.
     */
    @SuppressWarnings("unchecked")
    public <V> OptimizableSurface<V> get(String surfaceType) {
        OptimizableSurface<?> s = bySurfaceType.get(surfaceType);
        if (s == null) {
            throw new IllegalArgumentException(
                    "Unknown surface type: '" + surfaceType + "' (known: " + bySurfaceType.keySet() + ")");
        }
        return (OptimizableSurface<V>) s;
    }

    /** Known surface_type values present in this registry (defensive copy). */
    public Set<String> knownSurfaceTypes() {
        return Collections.unmodifiableSet(bySurfaceType.keySet());
    }
}
