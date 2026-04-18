package com.skillforge.server.code;

import com.skillforge.server.entity.ScriptMethodEntity;
import com.skillforge.server.hook.BuiltInMethodRegistry;
import com.skillforge.server.repository.ScriptMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup loader: reads all enabled {@link ScriptMethodEntity} rows and registers each
 * into {@link BuiltInMethodRegistry} under the {@code agent.*} namespace.
 *
 * <p>Runs after Spring context initialization so {@code BuiltInMethodRegistry} has already
 * collected {@code builtin.*} beans.
 */
@Component
@Order(100)
public class ScriptMethodLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScriptMethodLoader.class);

    private final ScriptMethodRepository repository;
    private final ScriptMethodService scriptMethodService;
    private final BuiltInMethodRegistry registry;

    public ScriptMethodLoader(ScriptMethodRepository repository,
                              ScriptMethodService scriptMethodService,
                              BuiltInMethodRegistry registry) {
        this.repository = repository;
        this.scriptMethodService = scriptMethodService;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ScriptMethodEntity> enabled = repository.findByEnabledTrue();
        int registered = 0;
        int skipped = 0;
        for (ScriptMethodEntity e : enabled) {
            try {
                registry.register(scriptMethodService.wrap(e));
                registered++;
            } catch (RuntimeException ex) {
                log.warn("ScriptMethodLoader: failed to register ref={}: {}", e.getRef(), ex.getMessage());
                skipped++;
            }
        }
        log.info("ScriptMethodLoader: registered {} script method(s), skipped {}", registered, skipped);
    }
}
