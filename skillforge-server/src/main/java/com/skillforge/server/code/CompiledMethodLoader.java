package com.skillforge.server.code;

import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.server.entity.CompiledMethodEntity;
import com.skillforge.server.hook.BuiltInMethodRegistry;
import com.skillforge.server.repository.CompiledMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup loader for active {@link CompiledMethodEntity} rows: loads class bytes via
 * {@link GeneratedMethodClassLoader}, instantiates, and registers in {@link BuiltInMethodRegistry}.
 *
 * <p>Runs after {@code ScriptMethodLoader} (order 100) so {@code agent.*} script methods are in
 * place before compiled methods attempt to register.
 */
@Component
@Order(101)
public class CompiledMethodLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CompiledMethodLoader.class);

    private final CompiledMethodRepository repository;
    private final CompiledMethodService service;
    private final BuiltInMethodRegistry registry;

    public CompiledMethodLoader(CompiledMethodRepository repository,
                                CompiledMethodService service,
                                BuiltInMethodRegistry registry) {
        this.repository = repository;
        this.service = service;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<CompiledMethodEntity> active = repository.findByStatus(CompiledMethodEntity.STATUS_ACTIVE);
        int registered = 0;
        int skipped = 0;
        for (CompiledMethodEntity e : active) {
            try {
                BuiltInMethod instance = service.loadAndInstantiate(e);
                registry.register(instance);
                registered++;
            } catch (RuntimeException ex) {
                log.warn("CompiledMethodLoader: failed to register ref={}: {}", e.getRef(), ex.getMessage());
                skipped++;
            }
        }
        log.info("CompiledMethodLoader: registered {} compiled method(s), skipped {}", registered, skipped);
    }
}
