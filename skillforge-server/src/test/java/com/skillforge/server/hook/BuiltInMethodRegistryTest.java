package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BuiltInMethodRegistry}.
 */
class BuiltInMethodRegistryTest {

    private static BuiltInMethod stub(String ref) {
        return new BuiltInMethod() {
            @Override public String ref() { return ref; }
            @Override public String displayName() { return ref + " display"; }
            @Override public String description() { return ref + " desc"; }
            @Override public Map<String, String> argsSchema() { return Map.of(); }
            @Override public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
                return HookRunResult.ok("ok", 0);
            }
        };
    }

    @Test
    @DisplayName("get returns method when registered")
    void get_registered_returnsMethod() {
        BuiltInMethod m = stub("builtin.test.one");
        BuiltInMethodRegistry registry = new BuiltInMethodRegistry(List.of(m));

        Optional<BuiltInMethod> result = registry.get("builtin.test.one");

        assertThat(result).isPresent();
        assertThat(result.get().ref()).isEqualTo("builtin.test.one");
    }

    @Test
    @DisplayName("get returns empty when method not registered")
    void get_notRegistered_returnsEmpty() {
        BuiltInMethodRegistry registry = new BuiltInMethodRegistry(List.of(stub("builtin.a")));

        Optional<BuiltInMethod> result = registry.get("builtin.nonexistent");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get returns empty for null ref")
    void get_nullRef_returnsEmpty() {
        BuiltInMethodRegistry registry = new BuiltInMethodRegistry(List.of(stub("builtin.a")));

        assertThat(registry.get(null)).isEmpty();
    }

    @Test
    @DisplayName("get returns empty for blank ref")
    void get_blankRef_returnsEmpty() {
        BuiltInMethodRegistry registry = new BuiltInMethodRegistry(List.of(stub("builtin.a")));

        assertThat(registry.get("  ")).isEmpty();
    }

    @Test
    @DisplayName("listAll returns all registered methods")
    void listAll_returnsAllMethods() {
        BuiltInMethod m1 = stub("builtin.a");
        BuiltInMethod m2 = stub("builtin.b");
        BuiltInMethodRegistry registry = new BuiltInMethodRegistry(List.of(m1, m2));

        List<BuiltInMethod> all = registry.listAll();

        assertThat(all).hasSize(2);
        assertThat(all).extracting(BuiltInMethod::ref).containsExactlyInAnyOrder("builtin.a", "builtin.b");
    }

    @Test
    @DisplayName("listAll returns immutable list")
    void listAll_returnsImmutableList() {
        BuiltInMethodRegistry registry = new BuiltInMethodRegistry(List.of(stub("builtin.a")));

        List<BuiltInMethod> all = registry.listAll();

        assertThat(all).isUnmodifiable();
    }

    @Test
    @DisplayName("duplicate ref keeps first registered, ignores second")
    void duplicateRef_keepsFirst() {
        BuiltInMethod first = stub("builtin.dup");
        BuiltInMethod second = new BuiltInMethod() {
            @Override public String ref() { return "builtin.dup"; }
            @Override public String displayName() { return "second"; }
            @Override public String description() { return ""; }
            @Override public Map<String, String> argsSchema() { return Map.of(); }
            @Override public HookRunResult execute(Map<String, Object> args, HookExecutionContext ctx) {
                return HookRunResult.ok("second", 0);
            }
        };
        BuiltInMethodRegistry registry = new BuiltInMethodRegistry(List.of(first, second));

        assertThat(registry.get("builtin.dup").get().displayName()).isEqualTo("builtin.dup display");
        assertThat(registry.listAll()).hasSize(1);
    }
}
