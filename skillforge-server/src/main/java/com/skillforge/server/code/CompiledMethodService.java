package com.skillforge.server.code;

import com.skillforge.core.engine.DangerousCommandChecker;
import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.server.entity.CompiledMethodEntity;
import com.skillforge.server.hook.BuiltInMethodRegistry;
import com.skillforge.server.repository.CompiledMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CRUD + lifecycle service for {@link CompiledMethodEntity}.
 *
 * <p>Flow: {@code submit()} → {@code compile()} → {@code approve()} (registers in
 * {@link BuiltInMethodRegistry}) or {@code reject()}.
 */
@Service
public class CompiledMethodService {

    private static final Logger log = LoggerFactory.getLogger(CompiledMethodService.class);

    private static final String REF_PREFIX = "agent.";
    private static final int MAX_REF_LENGTH = 128;
    private static final int MAX_SOURCE_CHARS = 65_536;

    private final CompiledMethodRepository repository;
    private final DynamicMethodCompiler compiler;
    private final BuiltInMethodRegistry registry;

    /** Keeps a reference to each loader so the loaded class is not prematurely unloaded. */
    private final Map<String, GeneratedMethodClassLoader> activeLoaders = new ConcurrentHashMap<>();

    public CompiledMethodService(CompiledMethodRepository repository,
                                 DynamicMethodCompiler compiler,
                                 BuiltInMethodRegistry registry) {
        this.repository = repository;
        this.compiler = compiler;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<CompiledMethodEntity> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<CompiledMethodEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<CompiledMethodEntity> findByRef(String ref) {
        return repository.findByRef(ref);
    }

    @Transactional(readOnly = true)
    public List<CompiledMethodEntity> findByStatus(String status) {
        return repository.findByStatus(status);
    }

    @Transactional
    public CompiledMethodEntity submit(SubmitRequest req) {
        validateSubmit(req);
        if (repository.existsByRef(req.ref())) {
            throw new CompiledMethodException("compiled method already exists: " + req.ref());
        }
        CompiledMethodEntity e = new CompiledMethodEntity();
        e.setRef(req.ref());
        e.setDisplayName(req.displayName());
        e.setDescription(req.description());
        e.setSourceCode(req.sourceCode());
        e.setArgsSchema(req.argsSchema());
        e.setGeneratedBySessionId(req.sessionId());
        e.setGeneratedByAgentId(req.agentId());
        e.setStatus(CompiledMethodEntity.STATUS_PENDING_REVIEW);
        CompiledMethodEntity saved = repository.save(e);
        log.info("Submitted compiled method ref={} id={} session={} agent={}",
                saved.getRef(), saved.getId(), saved.getGeneratedBySessionId(), saved.getGeneratedByAgentId());
        return saved;
    }

    @Transactional
    public CompiledMethodEntity compile(Long id) {
        CompiledMethodEntity e = repository.findById(id)
                .orElseThrow(() -> new CompiledMethodException("compiled method not found: id=" + id));
        if (!CompiledMethodEntity.STATUS_PENDING_REVIEW.equals(e.getStatus())
                && !CompiledMethodEntity.STATUS_REJECTED.equals(e.getStatus())) {
            throw new CompiledMethodException(
                    "can only compile methods in status 'pending_review' or 'rejected', current=" + e.getStatus());
        }
        DynamicMethodCompiler.CompilationResult result = compiler.compile(e.getSourceCode());
        if (!result.success()) {
            e.setCompileError(String.join("\n", result.errors()));
            e.setCompiledClassBytes(null);
            e.setStatus(CompiledMethodEntity.STATUS_PENDING_REVIEW);
            log.warn("Compile failed for ref={} id={}: {}", e.getRef(), e.getId(), result.errors());
            return repository.save(e);
        }
        e.setCompiledClassBytes(result.classBytes());
        e.setCompileError(null);
        e.setStatus(CompiledMethodEntity.STATUS_COMPILED);
        log.info("Compiled method ref={} id={} className={} bytes={}",
                e.getRef(), e.getId(), result.className(), result.classBytes().length);
        return repository.save(e);
    }

    @Transactional
    public CompiledMethodEntity approve(Long id, Long reviewerUserId) {
        CompiledMethodEntity e = repository.findById(id)
                .orElseThrow(() -> new CompiledMethodException("compiled method not found: id=" + id));
        if (!CompiledMethodEntity.STATUS_COMPILED.equals(e.getStatus())) {
            throw new CompiledMethodException(
                    "can only approve methods in status 'compiled', current=" + e.getStatus());
        }
        if (e.getCompiledClassBytes() == null || e.getCompiledClassBytes().length == 0) {
            throw new CompiledMethodException("no compiled bytes on record for id=" + id);
        }
        BuiltInMethod instance = loadAndInstantiate(e);
        e.setStatus(CompiledMethodEntity.STATUS_ACTIVE);
        e.setReviewedByUserId(reviewerUserId);
        CompiledMethodEntity saved = repository.save(e);
        registry.register(instance);
        log.info("Approved compiled method ref={} id={} reviewerUserId={}",
                saved.getRef(), saved.getId(), reviewerUserId);
        return saved;
    }

    @Transactional
    public CompiledMethodEntity reject(Long id, Long reviewerUserId) {
        CompiledMethodEntity e = repository.findById(id)
                .orElseThrow(() -> new CompiledMethodException("compiled method not found: id=" + id));
        if (CompiledMethodEntity.STATUS_ACTIVE.equals(e.getStatus())) {
            unloadIfActive(e.getRef());
        }
        e.setStatus(CompiledMethodEntity.STATUS_REJECTED);
        e.setReviewedByUserId(reviewerUserId);
        CompiledMethodEntity saved = repository.save(e);
        log.info("Rejected compiled method ref={} id={} reviewerUserId={}",
                saved.getRef(), saved.getId(), reviewerUserId);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        CompiledMethodEntity e = repository.findById(id)
                .orElseThrow(() -> new CompiledMethodException("compiled method not found: id=" + id));
        unloadIfActive(e.getRef());
        repository.delete(e);
        log.info("Deleted compiled method ref={} id={}", e.getRef(), id);
    }

    /**
     * Startup + approve path: load the persisted bytes, instantiate the class, and return it.
     * The resulting instance must implement {@link BuiltInMethod}.
     */
    public BuiltInMethod loadAndInstantiate(CompiledMethodEntity entity) {
        byte[] bytes = entity.getCompiledClassBytes();
        if (bytes == null || bytes.length == 0) {
            throw new CompiledMethodException("missing compiled bytes for ref=" + entity.getRef());
        }
        String className = DynamicMethodCompiler.extractSimpleClassName(entity.getSourceCode());
        String pkg = DynamicMethodCompiler.extractPackage(entity.getSourceCode());
        if (className == null) {
            throw new CompiledMethodException("cannot locate class name in source for ref=" + entity.getRef());
        }
        String fqcn = (pkg == null || pkg.isBlank()) ? className : pkg + "." + className;

        unloadIfActive(entity.getRef());
        GeneratedMethodClassLoader loader = new GeneratedMethodClassLoader(
                getClass().getClassLoader(), fqcn, bytes);
        try {
            Class<?> loaded = loader.loadGeneratedClass();
            if (!BuiltInMethod.class.isAssignableFrom(loaded)) {
                throw new CompiledMethodException(
                        "generated class " + fqcn + " does not implement BuiltInMethod");
            }
            Object instance = loaded.getDeclaredConstructor().newInstance();
            BuiltInMethod method = (BuiltInMethod) instance;
            if (!entity.getRef().equals(method.ref())) {
                throw new CompiledMethodException(
                        "ref mismatch: entity.ref=" + entity.getRef() + " but method.ref=" + method.ref());
            }
            activeLoaders.put(entity.getRef(), loader);
            return method;
        } catch (CompiledMethodException ex) {
            throw ex;
        } catch (ReflectiveOperationException ex) {
            throw new CompiledMethodException(
                    "failed to instantiate generated class " + fqcn + ": " + ex.getMessage());
        }
    }

    private void unloadIfActive(String ref) {
        try {
            registry.unregister(ref);
        } catch (RuntimeException ignored) {
            // registry may reject builtin.* refs; swallowed because ref is always agent.* here
        }
        GeneratedMethodClassLoader prev = activeLoaders.remove(ref);
        if (prev != null) {
            prev.unload();
        }
    }

    private void validateSubmit(SubmitRequest req) {
        if (req == null) {
            throw new CompiledMethodException("request is required");
        }
        if (req.ref() == null || req.ref().isBlank()) {
            throw new CompiledMethodException("ref is required");
        }
        if (!req.ref().startsWith(REF_PREFIX)) {
            throw new CompiledMethodException("ref must start with '" + REF_PREFIX + "': " + req.ref());
        }
        if (req.ref().length() > MAX_REF_LENGTH) {
            throw new CompiledMethodException("ref too long (max " + MAX_REF_LENGTH + ")");
        }
        if (req.sourceCode() == null || req.sourceCode().isBlank()) {
            throw new CompiledMethodException("sourceCode is required");
        }
        if (req.sourceCode().length() > MAX_SOURCE_CHARS) {
            throw new CompiledMethodException("sourceCode too long (max " + MAX_SOURCE_CHARS + " chars)");
        }
        String dangerous = DangerousCommandChecker.firstDangerousMatch(req.sourceCode());
        if (dangerous != null) {
            log.warn("Dangerous pattern in sourceCode during submit ref={}: {}", req.ref(), dangerous);
            throw new CompiledMethodException("sourceCode contains a disallowed pattern");
        }
    }

    public record SubmitRequest(
            String ref,
            String displayName,
            String description,
            String sourceCode,
            String argsSchema,
            String sessionId,
            Long agentId
    ) {}

    public static final class CompiledMethodException extends RuntimeException {
        public CompiledMethodException(String message) {
            super(message);
        }
    }
}
