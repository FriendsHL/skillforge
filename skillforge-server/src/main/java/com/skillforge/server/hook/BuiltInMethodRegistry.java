package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.BuiltInMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit whitelist registry for {@link BuiltInMethod} implementations.
 *
 * <p>All known methods are collected at startup via Spring constructor injection.
 * Lookup is by {@link BuiltInMethod#ref()} — never by reflection or class name.
 */
@Component
public class BuiltInMethodRegistry {

    private static final Logger log = LoggerFactory.getLogger(BuiltInMethodRegistry.class);

    private final Map<String, BuiltInMethod> methods;

    public BuiltInMethodRegistry(List<BuiltInMethod> methodBeans) {
        Map<String, BuiltInMethod> map = new LinkedHashMap<>();
        for (BuiltInMethod m : methodBeans) {
            String ref = m.ref();
            if (map.containsKey(ref)) {
                log.warn("Duplicate BuiltInMethod ref '{}' — keeping first, ignoring {}", ref, m.getClass().getSimpleName());
                continue;
            }
            map.put(ref, m);
        }
        this.methods = Map.copyOf(map);
        log.info("BuiltInMethodRegistry initialized: {} method(s) {}", methods.size(), methods.keySet());
    }

    /** Lookup a method by its ref key. Returns empty if not registered. */
    public Optional<BuiltInMethod> get(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(methods.get(ref));
    }

    /** List all registered methods — used by the REST API. */
    public List<BuiltInMethod> listAll() {
        return List.copyOf(methods.values());
    }
}
