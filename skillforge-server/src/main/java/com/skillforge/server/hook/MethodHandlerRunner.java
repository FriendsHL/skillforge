package com.skillforge.server.hook;

import com.skillforge.core.engine.hook.BuiltInMethod;
import com.skillforge.core.engine.hook.HandlerRunner;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookHandler;
import com.skillforge.core.engine.hook.HookRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * P2 {@link HandlerRunner} — looks up the named method in {@link BuiltInMethodRegistry}
 * and invokes it.
 *
 * <p>Static {@code handler.args} are merged with per-event runtime input. Runtime input keys
 * override any matching args keys — declared defaults should not shadow the live event payload.
 */
@Component
public class MethodHandlerRunner implements HandlerRunner<HookHandler.MethodHandler> {

    private static final Logger log = LoggerFactory.getLogger(MethodHandlerRunner.class);

    private final BuiltInMethodRegistry registry;

    public MethodHandlerRunner(BuiltInMethodRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<HookHandler.MethodHandler> handlerType() {
        return HookHandler.MethodHandler.class;
    }

    @Override
    public HookRunResult run(HookHandler.MethodHandler handler,
                             Map<String, Object> input,
                             HookExecutionContext ctx) {
        long t0 = System.currentTimeMillis();

        String methodRef = handler.getMethodRef();
        if (methodRef == null || methodRef.isBlank()) {
            return HookRunResult.failure("method_ref_missing", elapsed(t0));
        }

        Optional<BuiltInMethod> methodOpt = registry.get(methodRef);
        if (methodOpt.isEmpty()) {
            log.warn("Lifecycle hook method not found: '{}' (session={})", methodRef, ctx.sessionId());
            return HookRunResult.failure("method_not_found:" + methodRef, elapsed(t0));
        }

        BuiltInMethod method = methodOpt.get();

        // Merge static handler.args with runtime input; runtime wins on collisions.
        Map<String, Object> merged = new HashMap<>();
        if (handler.getArgs() != null) merged.putAll(handler.getArgs());
        if (input != null) merged.putAll(input);

        try {
            return method.execute(merged, ctx);
        } catch (Exception e) {
            long dur = elapsed(t0);
            log.warn("Lifecycle hook method '{}' threw: {}", methodRef, e.toString());
            return HookRunResult.failure("method_execution_error", dur);
        }
    }

    private static long elapsed(long t0) {
        return System.currentTimeMillis() - t0;
    }
}
