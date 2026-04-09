package com.skillforge.server.engine;

import com.skillforge.core.engine.CancellationRegistry;
import com.skillforge.core.engine.LoopContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationRegistryTest {

    @Test
    void register_then_cancel_returns_true_and_marks_context() {
        CancellationRegistry reg = new CancellationRegistry();
        LoopContext ctx = new LoopContext();
        reg.register("s1", ctx);

        assertThat(reg.isRunning("s1")).isTrue();
        assertThat(ctx.isCancelled()).isFalse();

        boolean ok = reg.cancel("s1");

        assertThat(ok).isTrue();
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    void cancel_unknown_session_returns_false() {
        CancellationRegistry reg = new CancellationRegistry();
        assertThat(reg.cancel("does-not-exist")).isFalse();
        assertThat(reg.isRunning("does-not-exist")).isFalse();
    }

    @Test
    void unregister_removes_context_and_makes_later_cancel_return_false() {
        CancellationRegistry reg = new CancellationRegistry();
        LoopContext ctx = new LoopContext();
        reg.register("s2", ctx);
        assertThat(reg.isRunning("s2")).isTrue();

        reg.unregister("s2");

        assertThat(reg.isRunning("s2")).isFalse();
        assertThat(reg.cancel("s2")).isFalse();
        assertThat(ctx.isCancelled()).isFalse();
    }
}
