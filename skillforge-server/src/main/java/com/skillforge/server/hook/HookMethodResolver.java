package com.skillforge.server.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.hook.FailurePolicy;
import com.skillforge.core.engine.hook.HookEntry;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.entity.CompiledMethodEntity;
import com.skillforge.server.repository.CompiledMethodRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class HookMethodResolver {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CompiledMethodRepository compiledMethodRepository;
    private final BuiltInMethodRegistry methodRegistry;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedBuiltinMethodRefs;

    public HookMethodResolver(CompiledMethodRepository compiledMethodRepository,
                              BuiltInMethodRegistry methodRegistry,
                              ObjectMapper objectMapper,
                              @Value("${lifecycle.hooks.agent-authored.allowed-builtin-methods:}")
                              Collection<String> allowedBuiltinMethodRefs) {
        this.compiledMethodRepository = compiledMethodRepository;
        this.methodRegistry = methodRegistry;
        this.objectMapper = objectMapper;
        this.allowedBuiltinMethodRefs = normalizeRefs(allowedBuiltinMethodRefs);
    }

    public MethodTarget resolveProposalTarget(String rawTarget) {
        if (rawTarget == null || rawTarget.isBlank()) {
            throw new IllegalArgumentException("methodTarget is required");
        }
        String target = rawTarget.trim();
        if (target.startsWith("compiled:")) {
            Long id = parseId(target.substring("compiled:".length()), "compiled method id");
            CompiledMethodEntity method = compiledMethodRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("compiled method not found: id=" + id));
            if (!CompiledMethodEntity.STATUS_ACTIVE.equals(method.getStatus())) {
                throw new IllegalArgumentException("compiled method is not active: id=" + id);
            }
            if (method.getCompiledClassBytes() == null || method.getCompiledClassBytes().length == 0) {
                throw new IllegalArgumentException("compiled method has no compiled bytes: id=" + id);
            }
            if (methodRegistry.get(method.getRef()).isEmpty()) {
                throw new IllegalArgumentException("compiled method is not registered: " + method.getRef());
            }
            return new MethodTarget(
                    AgentAuthoredHookEntity.METHOD_KIND_COMPILED,
                    method.getId(),
                    method.getRef(),
                    sha256(method.getCompiledClassBytes()));
        }
        if (target.startsWith("builtin:")) {
            String ref = target.substring("builtin:".length()).trim();
            if (!ref.startsWith("builtin.")) {
                throw new IllegalArgumentException("builtin method target must reference builtin.*: " + ref);
            }
            if (!allowedBuiltinMethodRefs.contains(ref)) {
                throw new IllegalArgumentException("builtin method is not allowlisted for agent-authored hooks: " + ref);
            }
            if (methodRegistry.get(ref).isEmpty()) {
                throw new IllegalArgumentException("builtin method not found: " + ref);
            }
            return new MethodTarget(AgentAuthoredHookEntity.METHOD_KIND_BUILTIN, null, ref, null);
        }
        throw new IllegalArgumentException("methodTarget must be compiled:<id> or builtin:<ref>");
    }

    public HookEntry toHookEntry(AgentAuthoredHookEntity entity) {
        validateStoredTarget(entity);
        HookHandler.MethodHandler handler = new HookHandler.MethodHandler();
        handler.setMethodRef(entity.getMethodRef());
        handler.setArgs(parseArgs(entity.getArgsJson()));

        HookEntry entry = new HookEntry();
        entry.setHandler(handler);
        entry.setTimeoutSeconds(entity.getTimeoutSeconds());
        entry.setFailurePolicy(FailurePolicy.fromJson(entity.getFailurePolicy()));
        entry.setAsync(entity.isAsync());
        entry.setDisplayName(entity.getDisplayName());
        return entry;
    }

    public void validateStoredTarget(AgentAuthoredHookEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("hook is required");
        }
        String kind = entity.getMethodKind();
        if (AgentAuthoredHookEntity.METHOD_KIND_COMPILED.equals(kind)) {
            if (entity.getMethodId() == null) {
                throw new IllegalArgumentException("compiled method id is required");
            }
            CompiledMethodEntity method = compiledMethodRepository.findById(entity.getMethodId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "compiled method not found: id=" + entity.getMethodId()));
            if (!CompiledMethodEntity.STATUS_ACTIVE.equals(method.getStatus())) {
                throw new IllegalArgumentException("compiled method is not active: id=" + entity.getMethodId());
            }
            String hash = sha256(method.getCompiledClassBytes());
            if (!method.getRef().equals(entity.getMethodRef())
                    || !hash.equals(entity.getMethodVersionHash())) {
                throw new IllegalArgumentException("compiled method target changed for hook id=" + entity.getId());
            }
            if (methodRegistry.get(method.getRef()).isEmpty()) {
                throw new IllegalArgumentException("compiled method is not registered: " + method.getRef());
            }
            return;
        }
        if (AgentAuthoredHookEntity.METHOD_KIND_BUILTIN.equals(kind)) {
            String ref = entity.getMethodRef();
            if (ref == null || !ref.startsWith("builtin.")) {
                throw new IllegalArgumentException("invalid builtin method ref: " + ref);
            }
            if (!allowedBuiltinMethodRefs.contains(ref)) {
                throw new IllegalArgumentException("builtin method is not allowlisted for agent-authored hooks: " + ref);
            }
            if (methodRegistry.get(ref).isEmpty()) {
                throw new IllegalArgumentException("builtin method not found: " + ref);
            }
            return;
        }
        throw new IllegalArgumentException("unsupported method kind: " + kind);
    }

    private static Set<String> normalizeRefs(Collection<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return Set.of();
        }
        return refs.stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .map(String::trim)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid argsJson");
        }
    }

    public String argsToJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid args");
        }
    }

    private static Long parseId(String raw, String label) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + label + ": " + raw);
        }
    }

    private static String sha256(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("compiled method bytes are empty");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record MethodTarget(String methodKind, Long methodId, String methodRef, String methodVersionHash) {}
}
