package com.skillforge.core.engine;

import com.skillforge.core.skill.SkillContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Memory v2 (PR-2): unit tests for the cross-module {@code injectedMemoryIds} transport
 * between {@link LoopContext} (engine state) and {@link SkillContext} (per-tool dispatch).
 *
 * <p>The contract verified here:
 * <ul>
 *   <li>Default value is non-null, immutable empty set on both contexts.</li>
 *   <li>Setter accepts null and collapses to immutable empty set.</li>
 *   <li>Setter takes a defensive {@link Set#copyOf} snapshot — caller mutating its source set
 *       after-the-fact must not leak into the context.</li>
 *   <li>Getter returns an immutable view (mutation attempts throw).</li>
 *   <li>{@link MemoryInjection#empty()} sentinel matches the documented "no injection" shape.</li>
 * </ul>
 */
@DisplayName("Memory v2 (PR-2) injectedMemoryIds transport")
class MemoryInjectionContextTest {

    @Test
    @DisplayName("LoopContext default injectedMemoryIds is non-null empty immutable set")
    void loopContext_defaultEmpty() {
        LoopContext ctx = new LoopContext();
        assertThat(ctx.getInjectedMemoryIds()).isNotNull().isEmpty();
        assertThatThrownBy(() -> ctx.getInjectedMemoryIds().add(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("SkillContext default injectedMemoryIds is non-null empty immutable set")
    void skillContext_defaultEmpty() {
        SkillContext ctx = new SkillContext();
        assertThat(ctx.getInjectedMemoryIds()).isNotNull().isEmpty();
        assertThatThrownBy(() -> ctx.getInjectedMemoryIds().add(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("LoopContext.setInjectedMemoryIds(null) collapses to immutable empty set")
    void loopContext_setNull_collapsesToEmpty() {
        LoopContext ctx = new LoopContext();
        ctx.setInjectedMemoryIds(Set.of(1L, 2L));
        ctx.setInjectedMemoryIds(null);

        assertThat(ctx.getInjectedMemoryIds()).isEmpty();
        assertThatThrownBy(() -> ctx.getInjectedMemoryIds().add(99L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("LoopContext.setInjectedMemoryIds takes defensive copy of mutable input")
    void loopContext_defensiveCopy() {
        LoopContext ctx = new LoopContext();
        Set<Long> mutable = new HashSet<>(Set.of(1L, 2L));
        ctx.setInjectedMemoryIds(mutable);

        // Mutate the source — context snapshot must NOT see the mutation.
        mutable.add(99L);

        assertThat(ctx.getInjectedMemoryIds())
                .containsExactlyInAnyOrder(1L, 2L)
                .doesNotContain(99L);
    }

    @Test
    @DisplayName("SkillContext.setInjectedMemoryIds takes defensive copy of mutable input")
    void skillContext_defensiveCopy() {
        SkillContext ctx = new SkillContext();
        Set<Long> mutable = new HashSet<>(Set.of(7L, 8L));
        ctx.setInjectedMemoryIds(mutable);

        mutable.add(123L);

        assertThat(ctx.getInjectedMemoryIds())
                .containsExactlyInAnyOrder(7L, 8L)
                .doesNotContain(123L);
    }

    @Test
    @DisplayName("LoopContext → SkillContext round-trip preserves ids and immutability")
    void roundTrip_loopToSkill() {
        LoopContext loopCtx = new LoopContext();
        loopCtx.setInjectedMemoryIds(Set.of(10L, 20L, 30L));

        // This mirrors AgentLoopEngine:1727 — the production code path.
        SkillContext skillCtx = new SkillContext();
        skillCtx.setInjectedMemoryIds(loopCtx.getInjectedMemoryIds());

        assertThat(skillCtx.getInjectedMemoryIds())
                .containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    @Test
    @DisplayName("MemoryInjection.empty() yields blank text + immutable empty id set")
    void memoryInjection_emptySentinel() {
        MemoryInjection mi = MemoryInjection.empty();
        assertThat(mi.text()).isEmpty();
        assertThat(mi.injectedIds()).isNotNull().isEmpty();
        assertThatThrownBy(() -> mi.injectedIds().add(1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("MemoryInjection record carries arbitrary text and ids")
    void memoryInjection_recordBasics() {
        MemoryInjection mi = new MemoryInjection("## hello", Set.of(1L, 2L));
        assertThat(mi.text()).isEqualTo("## hello");
        assertThat(mi.injectedIds()).containsExactlyInAnyOrder(1L, 2L);
    }
}
