package com.skillforge.core.engine;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MULTIMODAL-MVP r2 (B2 fix) regression test.
 *
 * <p>Iron Law: the engine's {@code messages} list and the DB-persisted row both
 * keep the reference shape ({@code image_ref}). Materialization (base64
 * {@code image} blocks) lives ONLY inside the transient request copy built
 * by {@link MessageMaterializer#expandForProvider} right before the provider
 * call. If this property breaks, {@code SessionService.updateSessionMessages}
 * triggers a mid-prefix divergence guard rewrite that overwrites the DB with
 * base64 image bytes (~MB per turn) — the exact bug B2 prevents.</p>
 *
 * <p>This focused unit test on {@link AgentLoopEngine#applyMaterializer} locks
 * three properties:</p>
 * <ol>
 *   <li>No materializer wired → input list is returned unchanged (identity).</li>
 *   <li>Materializer returns the same list reference (no-op) → input list is
 *       returned unchanged.</li>
 *   <li>Materializer expands a block → returned list is a NEW reference, AND
 *       the original input list is NOT mutated (still in image_ref form).</li>
 * </ol>
 */
@DisplayName("AgentLoopEngine.applyMaterializer — engine-boundary materialization (B2 fix)")
class AgentLoopEngineMaterializerTest {

    @Test
    @DisplayName("no materializer wired → input list returned by identity")
    void noMaterializer_returnsInputUnchanged() {
        LoopContext ctx = new LoopContext();
        ctx.setSessionId("sess-1");
        // intentionally NOT calling ctx.setMessageMaterializer(...)
        List<Message> input = List.of(userMessageWithImageRef());

        List<Message> result = AgentLoopEngine.applyMaterializer(ctx, input);

        assertThat(result).isSameAs(input);
    }

    @Test
    @DisplayName("null inputs → returns input unchanged (no NPE)")
    void nullInputs_noNpe() {
        assertThat(AgentLoopEngine.applyMaterializer(null, null)).isNull();
        assertThat(AgentLoopEngine.applyMaterializer(new LoopContext(), null)).isNull();
        assertThat(AgentLoopEngine.applyMaterializer(null, List.of())).isEqualTo(List.of());
    }

    @Test
    @DisplayName("materializer no-op (returns input) → input returned by identity")
    void materializerNoOp_returnsInputUnchanged() {
        LoopContext ctx = new LoopContext();
        ctx.setSessionId("sess-1");
        AtomicInteger calls = new AtomicInteger();
        ctx.setMessageMaterializer((sessionId, messages) -> {
            calls.incrementAndGet();
            return messages; // no expansion needed
        });
        List<Message> input = List.of(Message.user("text only"));

        List<Message> result = AgentLoopEngine.applyMaterializer(ctx, input);

        assertThat(result).isSameAs(input);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("materializer expands → returns NEW list; original input NOT mutated")
    void materializerExpands_returnsCopyOriginalUnmutated() {
        LoopContext ctx = new LoopContext();
        ctx.setSessionId("sess-1");
        // Real-life expansion: image_ref → image with base64 bytes.
        ctx.setMessageMaterializer((sessionId, messages) -> {
            List<Message> expanded = new ArrayList<>(messages);
            for (int i = 0; i < expanded.size(); i++) {
                Message m = expanded.get(i);
                if (m.getContent() instanceof List<?> blocks
                        && !blocks.isEmpty()
                        && blocks.get(0) instanceof ContentBlock cb
                        && "image_ref".equals(cb.getType())) {
                    Message copy = new Message();
                    copy.setRole(m.getRole());
                    copy.setContent(List.of(ContentBlock.image("image/png", "BASE64_BYTES")));
                    expanded.set(i, copy);
                }
            }
            return expanded;
        });
        Message original = userMessageWithImageRef();
        List<Message> input = new ArrayList<>(List.of(original));

        List<Message> result = AgentLoopEngine.applyMaterializer(ctx, input);

        // Property 1: returned list is a different reference (expansion happened).
        assertThat(result).isNotSameAs(input);
        // Property 2: returned list contains the expanded form.
        Message returnedFirst = result.get(0);
        @SuppressWarnings("unchecked")
        List<ContentBlock> returnedBlocks = (List<ContentBlock>) returnedFirst.getContent();
        assertThat(returnedBlocks.get(0).getType()).isEqualTo("image");
        assertThat(returnedBlocks.get(0).getDataBase64()).isEqualTo("BASE64_BYTES");
        // Property 3 (the B2 Iron Law): the original input list is NOT mutated; the
        // Message at index 0 is still the original image_ref reference. This is what
        // prevents the divergence-guard rewrite of t_session_message with base64.
        assertThat(input.get(0)).isSameAs(original);
        @SuppressWarnings("unchecked")
        List<ContentBlock> originalBlocks = (List<ContentBlock>) original.getContent();
        assertThat(originalBlocks.get(0).getType()).isEqualTo("image_ref");
    }

    @Test
    @DisplayName("materializer throws → fall back to raw input (no crash, no silent corruption)")
    void materializerThrows_fallsBackToInput() {
        LoopContext ctx = new LoopContext();
        ctx.setSessionId("sess-1");
        ctx.setMessageMaterializer((sessionId, messages) -> {
            throw new IllegalStateException("simulated attachment-store failure");
        });
        List<Message> input = List.of(userMessageWithImageRef());

        List<Message> result = AgentLoopEngine.applyMaterializer(ctx, input);

        // Fall-back returns the raw input. Provider will see image_ref and likely
        // 400, surfaced to user via existing error path. Better than corrupting DB.
        assertThat(result).isSameAs(input);
    }

    // -------------------------- helpers --------------------------

    private static Message userMessageWithImageRef() {
        Message m = new Message();
        m.setRole(Message.Role.USER);
        m.setContent(new ArrayList<>(List.of(
                ContentBlock.imageRef("att-1", "image/png", "screen.png"))));
        return m;
    }
}
