package com.skillforge.server.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import com.skillforge.server.entity.AgentAuthoredHookEntity;
import com.skillforge.server.entity.CompiledMethodEntity;
import com.skillforge.server.repository.CompiledMethodRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HookMethodResolverTest {

    @Test
    void resolveProposalTarget_builtinRequiresExplicitAllowlist() {
        HookMethodResolver resolver = resolver(List.of(stubMethod("builtin.safe")), List.of());

        assertThatThrownBy(() -> resolver.resolveProposalTarget("builtin:builtin.safe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void resolveProposalTarget_allowlistedBuiltinResolves() {
        HookMethodResolver resolver = resolver(List.of(stubMethod("builtin.safe")), List.of("builtin.safe"));

        HookMethodResolver.MethodTarget target = resolver.resolveProposalTarget("builtin:builtin.safe");

        assertThat(target.methodKind()).isEqualTo(AgentAuthoredHookEntity.METHOD_KIND_BUILTIN);
        assertThat(target.methodRef()).isEqualTo("builtin.safe");
        assertThat(target.methodId()).isNull();
    }

    @Test
    void toHookEntry_storedBuiltinRevalidatesAllowlist() {
        HookMethodResolver resolver = resolver(List.of(stubMethod("builtin.safe")), List.of());
        AgentAuthoredHookEntity entity = new AgentAuthoredHookEntity();
        entity.setMethodKind(AgentAuthoredHookEntity.METHOD_KIND_BUILTIN);
        entity.setMethodRef("builtin.safe");

        assertThatThrownBy(() -> resolver.toHookEntry(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void resolveProposalTarget_compiledTargetStoresImmutableHash() {
        CompiledMethodEntity method = compiledMethod(5L, "agent.safe", CompiledMethodEntity.STATUS_ACTIVE,
                new byte[]{1, 2, 3});
        HookMethodResolver resolver = resolver(repositoryWith(method), List.of(stubMethod("agent.safe")), List.of());

        HookMethodResolver.MethodTarget target = resolver.resolveProposalTarget("compiled:5");

        assertThat(target.methodKind()).isEqualTo(AgentAuthoredHookEntity.METHOD_KIND_COMPILED);
        assertThat(target.methodId()).isEqualTo(5L);
        assertThat(target.methodRef()).isEqualTo("agent.safe");
        assertThat(target.methodVersionHash()).hasSize(64);
    }

    private static HookMethodResolver resolver(List<BuiltInMethod> methods, List<String> allowedBuiltinRefs) {
        return resolver(repositoryWith(null), methods, allowedBuiltinRefs);
    }

    private static HookMethodResolver resolver(CompiledMethodRepository repository,
                                               List<BuiltInMethod> methods,
                                               List<String> allowedBuiltinRefs) {
        return new HookMethodResolver(repository, new BuiltInMethodRegistry(methods), new ObjectMapper(),
                allowedBuiltinRefs);
    }

    private static CompiledMethodEntity compiledMethod(Long id, String ref, String status, byte[] bytes) {
        CompiledMethodEntity entity = new CompiledMethodEntity();
        entity.setId(id);
        entity.setRef(ref);
        entity.setStatus(status);
        entity.setCompiledClassBytes(bytes);
        return entity;
    }

    private static CompiledMethodRepository repositoryWith(CompiledMethodEntity entity) {
        return (CompiledMethodRepository) Proxy.newProxyInstance(
                HookMethodResolverTest.class.getClassLoader(),
                new Class[]{CompiledMethodRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Optional.ofNullable(entity);
                    case "toString" -> "CompiledMethodRepositoryStub";
                    default -> null;
                });
    }

    private static BuiltInMethod stubMethod(String ref) {
        return new BuiltInMethod() {
            @Override public String ref() { return ref; }
            @Override public String displayName() { return ref; }
            @Override public String description() { return ""; }
            @Override public Map<String, String> argsSchema() { return Map.of(); }
            @Override public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
                return HookRunResult.ok("ok", 0);
            }
        };
    }
}
