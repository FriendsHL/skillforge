package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.BuiltInMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for {@link BuiltInMethod} implementations.
 *
 * <p>Two namespaces:
 * <ul>
 *   <li><b>builtin.*</b> — reserved for Spring {@code @Component} beans collected at startup.
 *       Cannot be registered or unregistered via {@link #register}/{@link #unregister}.</li>
 *   <li><b>agent.*</b> — dynamic namespace for methods registered at runtime by Code Agent
 *       (ScriptMethod, CompiledMethod). These can be added/removed without restart.</li>
 * </ul>
 */
@Component
public class BuiltInMethodRegistry {

    private static final Logger log = LoggerFactory.getLogger(BuiltInMethodRegistry.class);

    static final String DYNAMIC_NAMESPACE_PREFIX = "agent.";
    static final String BUILTIN_NAMESPACE_PREFIX = "builtin.";

    private final Map<String, BuiltInMethod> methods;

    public BuiltInMethodRegistry(List<BuiltInMethod> methodBeans) {
        Map<String, BuiltInMethod> map = new ConcurrentHashMap<>();
        for (BuiltInMethod m : methodBeans) {
            String ref = m.ref();
            if (map.containsKey(ref)) {
                log.warn("Duplicate BuiltInMethod ref '{}' — keeping first, ignoring {}", ref, m.getClass().getSimpleName());
                continue;
            }
            map.put(ref, m);
        }
        this.methods = map;
        log.info("BuiltInMethodRegistry initialized: {} method(s) {}", methods.size(), methods.keySet());
    }

    /** Lookup a method by its ref key. Returns empty if not registered. */
    public Optional<BuiltInMethod> get(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(methods.get(ref));
    }

    /** Alias for {@link #get(String)} preserved for external callers. */
    public Optional<BuiltInMethod> findByRef(String ref) {
        return get(ref);
    }

    /** List all registered methods — used by the REST API. */
    public List<BuiltInMethod> listAll() {
        return List.copyOf(methods.values());
    }

    /**
     * Register a dynamic method under the {@code agent.*} namespace.
     * Rejects any ref in the reserved {@code builtin.*} namespace.
     *
     * @throws IllegalArgumentException when ref is null/blank, in the reserved namespace,
     *                                  or not prefixed with {@code agent.}.
     */
    public void register(BuiltInMethod method) {
        if (method == null) {
            throw new IllegalArgumentException("method must not be null");
        }
        String ref = method.ref();
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("method.ref must not be blank");
        }
        if (ref.startsWith(BUILTIN_NAMESPACE_PREFIX)) {
            throw new IllegalArgumentException("Cannot register into reserved namespace 'builtin.*': " + ref);
        }
        if (!ref.startsWith(DYNAMIC_NAMESPACE_PREFIX)) {
            throw new IllegalArgumentException("Dynamic method ref must start with 'agent.': " + ref);
        }
        BuiltInMethod prev = methods.put(ref, method);
        if (prev != null) {
            log.info("Replaced existing BuiltInMethod '{}' (class {} → {})",
                    ref, prev.getClass().getSimpleName(), method.getClass().getSimpleName());
        } else {
            log.info("Registered BuiltInMethod '{}' ({})", ref, method.getClass().getSimpleName());
        }
    }

    /**
     * Unregister a dynamic method. No-op if the ref is not currently registered.
     * Rejects refs in the reserved {@code builtin.*} namespace.
     *
     * @throws IllegalArgumentException when ref is null/blank or in the reserved namespace.
     */
    public void unregister(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("ref must not be blank");
        }
        if (ref.startsWith(BUILTIN_NAMESPACE_PREFIX)) {
            throw new IllegalArgumentException("Cannot unregister from reserved namespace 'builtin.*': " + ref);
        }
        BuiltInMethod removed = methods.remove(ref);
        if (removed != null) {
            log.info("Unregistered BuiltInMethod '{}' ({})", ref, removed.getClass().getSimpleName());
        }
    }
}
