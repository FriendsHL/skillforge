package com.skillforge.workflow.bindings;

import com.skillforge.workflow.sandbox.BudgetTracker;
import com.skillforge.workflow.sandbox.L1SandboxFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task B — {@link JsConversions} bidirectional Java ⇄ Rhino conversion, run
 * under the real L1 sandbox Context (the {@code ClassShutter} that would reject a
 * raw {@code Context.javaToJS(Map)} is active here — the footgun the util exists
 * to dodge).
 */
class JsConversionsTest {

    private final L1SandboxFactory factory = new L1SandboxFactory();

    private BudgetTracker budget() {
        return new BudgetTracker(BudgetTracker.DEFAULT_INSTRUCTION_CAP,
                BudgetTracker.DEFAULT_AGENT_CALL_CAP,
                System.nanoTime(),
                BudgetTracker.DEFAULT_TIMEOUT_NANOS);
    }

    @Test
    @DisplayName("Map/List → JS-native → back to Java round-trips (no ClassShutter rejection)")
    void mapListRoundTrip() {
        Map<String, Object> orig = new LinkedHashMap<>();
        orig.put("name", "wf");
        orig.put("count", 3L);
        orig.put("flag", true);
        orig.put("items", List.of("a", "b"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("k", "v");
        orig.put("nested", nested);

        Context cx = factory.enter(budget());
        try {
            Scriptable scope = factory.createSafeScope(cx);

            Object js = JsConversions.toJs(cx, scope, orig);
            assertThat(js).isInstanceOf(Scriptable.class);

            Object back = JsConversions.jsToJava(js);
            assertThat(back).isEqualTo(orig);
        } finally {
            Context.exit();
        }
    }

    @Test
    @DisplayName("jsToJava normalizes integral JS numbers to Long and passes primitives through")
    void numberNormalization() {
        Context cx = factory.enter(budget());
        try {
            Scriptable scope = factory.createSafeScope(cx);
            Object arr = JsConversions.toJs(cx, scope, List.of(1L, 2L, 3L));
            Object back = JsConversions.jsToJava(arr);
            assertThat(back).isEqualTo(List.of(1L, 2L, 3L));
        } finally {
            Context.exit();
        }
    }

    @Test
    @DisplayName("toJs passes a String through unchanged (no Map/List wrapping)")
    void stringPassthrough() {
        Context cx = factory.enter(budget());
        try {
            Scriptable scope = factory.createSafeScope(cx);
            Object js = JsConversions.toJs(cx, scope, "hello");
            assertThat(JsConversions.jsToJava(js)).isEqualTo("hello");
        } finally {
            Context.exit();
        }
    }
}
