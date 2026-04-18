package com.skillforge.server.code;

import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.server.entity.ScriptMethodEntity;
import com.skillforge.server.hook.BuiltInMethodRegistry;
import com.skillforge.server.hook.ScriptHandlerRunner;
import com.skillforge.server.repository.ScriptMethodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CRUD + live-registration service for {@link ScriptMethodEntity}.
 *
 * <p>On {@link #create(CreateRequest)}: persist entity → wrap as {@link BuiltInMethod} →
 * register in {@link BuiltInMethodRegistry}. On disable/delete: unregister.
 */
@Service
public class ScriptMethodService {

    private static final Logger log = LoggerFactory.getLogger(ScriptMethodService.class);

    private static final String REF_PREFIX = "agent.";
    private static final Set<String> ALLOWED_LANGS = Set.of("bash", "node");
    private static final int MAX_REF_LENGTH = 128;
    private static final int MAX_SCRIPT_BODY_CHARS = 16_384;

    private final ScriptMethodRepository repository;
    private final BuiltInMethodRegistry registry;
    private final ScriptHandlerRunner scriptHandlerRunner;

    public ScriptMethodService(ScriptMethodRepository repository,
                               BuiltInMethodRegistry registry,
                               ScriptHandlerRunner scriptHandlerRunner) {
        this.repository = repository;
        this.registry = registry;
        this.scriptHandlerRunner = scriptHandlerRunner;
    }

    @Transactional(readOnly = true)
    public List<ScriptMethodEntity> listAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<ScriptMethodEntity> findByRef(String ref) {
        return repository.findByRef(ref);
    }

    @Transactional(readOnly = true)
    public Optional<ScriptMethodEntity> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public ScriptMethodEntity create(CreateRequest req) {
        validate(req);
        if (repository.existsByRef(req.ref())) {
            throw new ScriptMethodException("Script method already exists: " + req.ref());
        }
        ScriptMethodEntity e = new ScriptMethodEntity();
        e.setRef(req.ref());
        e.setDisplayName(req.displayName());
        e.setDescription(req.description());
        e.setLang(req.lang().toLowerCase(Locale.ROOT));
        e.setScriptBody(req.scriptBody());
        e.setArgsSchema(req.argsSchema());
        e.setOwnerId(req.ownerId());
        e.setEnabled(true);
        ScriptMethodEntity saved = repository.save(e);
        registry.register(wrap(saved));
        log.info("Created script method ref={} lang={} owner={}", saved.getRef(), saved.getLang(), saved.getOwnerId());
        return saved;
    }

    @Transactional
    public ScriptMethodEntity update(Long id, UpdateRequest req) {
        ScriptMethodEntity e = repository.findById(id)
                .orElseThrow(() -> new ScriptMethodException("Script method not found: id=" + id));
        if (req.displayName() != null) e.setDisplayName(req.displayName());
        if (req.description() != null) e.setDescription(req.description());
        if (req.lang() != null) {
            String lang = req.lang().toLowerCase(Locale.ROOT);
            if (!ALLOWED_LANGS.contains(lang)) {
                throw new ScriptMethodException("lang must be bash or node: " + req.lang());
            }
            e.setLang(lang);
        }
        if (req.scriptBody() != null) {
            if (req.scriptBody().length() > MAX_SCRIPT_BODY_CHARS) {
                throw new ScriptMethodException("scriptBody too long: " + req.scriptBody().length());
            }
            e.setScriptBody(req.scriptBody());
        }
        if (req.argsSchema() != null) e.setArgsSchema(req.argsSchema());
        ScriptMethodEntity saved = repository.save(e);
        if (saved.isEnabled()) {
            registry.register(wrap(saved));
        }
        log.info("Updated script method ref={}", saved.getRef());
        return saved;
    }

    @Transactional
    public ScriptMethodEntity setEnabled(Long id, boolean enabled) {
        ScriptMethodEntity e = repository.findById(id)
                .orElseThrow(() -> new ScriptMethodException("Script method not found: id=" + id));
        if (e.isEnabled() == enabled) {
            return e;
        }
        e.setEnabled(enabled);
        ScriptMethodEntity saved = repository.save(e);
        if (enabled) {
            registry.register(wrap(saved));
        } else {
            registry.unregister(saved.getRef());
        }
        log.info("Toggled script method ref={} enabled={}", saved.getRef(), enabled);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        ScriptMethodEntity e = repository.findById(id)
                .orElseThrow(() -> new ScriptMethodException("Script method not found: id=" + id));
        registry.unregister(e.getRef());
        repository.delete(e);
        log.info("Deleted script method ref={}", e.getRef());
    }

    /**
     * Wrap the entity as a BuiltInMethod that delegates to ScriptHandlerRunner. Called on
     * startup by ScriptMethodLoader and on every create/update/enable.
     */
    public BuiltInMethod wrap(ScriptMethodEntity entity) {
        final String ref = entity.getRef();
        final String displayName = entity.getDisplayName();
        final String description = entity.getDescription() != null ? entity.getDescription() : "";
        final Map<String, String> schemaMap = parseArgsSchema(entity.getArgsSchema());
        final String lang = entity.getLang();
        final String body = entity.getScriptBody();

        return new BuiltInMethod() {
            @Override public String ref() { return ref; }
            @Override public String displayName() { return displayName; }
            @Override public String description() { return description; }
            @Override public Map<String, String> argsSchema() { return schemaMap; }
            @Override public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
                HookHandler.ScriptHandler handler = new HookHandler.ScriptHandler();
                handler.setScriptLang(lang);
                handler.setScriptBody(body);
                if (args != null) {
                    handler.setArgs(args);
                }
                return scriptHandlerRunner.run(handler, args, ctx);
            }
        };
    }

    private void validate(CreateRequest req) {
        if (req == null) {
            throw new ScriptMethodException("request is required");
        }
        if (req.ref() == null || req.ref().isBlank()) {
            throw new ScriptMethodException("ref is required");
        }
        if (!req.ref().startsWith(REF_PREFIX)) {
            throw new ScriptMethodException("ref must start with '" + REF_PREFIX + "': " + req.ref());
        }
        if (req.ref().length() > MAX_REF_LENGTH) {
            throw new ScriptMethodException("ref too long (max " + MAX_REF_LENGTH + ")");
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new ScriptMethodException("displayName is required");
        }
        if (req.lang() == null || !ALLOWED_LANGS.contains(req.lang().toLowerCase(Locale.ROOT))) {
            throw new ScriptMethodException("lang must be bash or node");
        }
        if (req.scriptBody() == null || req.scriptBody().isBlank()) {
            throw new ScriptMethodException("scriptBody is required");
        }
        if (req.scriptBody().length() > MAX_SCRIPT_BODY_CHARS) {
            throw new ScriptMethodException("scriptBody too long (max " + MAX_SCRIPT_BODY_CHARS + " chars)");
        }
    }

    private static Map<String, String> parseArgsSchema(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        // Lightweight: defer real parsing to the frontend; expose the raw JSON in a single key
        // so BuiltInMethod.argsSchema() contract is preserved (Map<String,String>).
        return Map.of("_raw", json);
    }

    public record CreateRequest(
            String ref,
            String displayName,
            String description,
            String lang,
            String scriptBody,
            String argsSchema,
            Long ownerId
    ) {}

    public record UpdateRequest(
            String displayName,
            String description,
            String lang,
            String scriptBody,
            String argsSchema
    ) {}

    public static final class ScriptMethodException extends RuntimeException {
        public ScriptMethodException(String message) {
            super(message);
        }
    }
}
