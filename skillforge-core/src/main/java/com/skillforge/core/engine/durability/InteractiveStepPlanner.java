package com.skillforge.core.engine.durability;

import com.skillforge.core.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure preflight planner for a provider Tool-call vector containing an interactive call.
 *
 * <p>The planner selects the first interactive call in provider order and exposes no executable
 * calls before that control is resolved. Once the selected control has a result, it constructs the
 * complete provider-ordered result vector: the selected result is retained and every sibling is
 * closed with an {@value #ABORTED_RESULT_ERROR_TYPE} placeholder.
 */
public final class InteractiveStepPlanner {

    public static final String ABORTED_RESULT_ERROR_TYPE =
            "ABORTED_FOR_INTERACTIVE_CONTROL";
    public static final String ABORTED_RESULT_CONTENT =
            "Tool call was not executed because another interactive control "
                    + "in the same assistant response was selected.";

    /** Classifies one immutable provider Tool call without performing any side effect. */
    @FunctionalInterface
    public interface CallClassifier {
        CallKind classify(ToolCallIntent call);
    }

    public enum CallKind {
        NORMAL,
        ASK_USER,
        CONFIRMATION;

        boolean isInteractive() {
            return this != NORMAL;
        }
    }

    /** Returns an interactive plan, or empty when the full vector contains no interactive call. */
    public Optional<InteractiveStepPlan> plan(
            ToolCallManifest manifest, CallClassifier classifier) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(classifier, "classifier");

        SelectedControl selected = null;
        for (ToolCallIntent call : manifest.calls()) {
            CallKind kind = Objects.requireNonNull(
                    classifier.classify(call), "classifier result");
            if (selected == null && kind.isInteractive()) {
                selected = new SelectedControl(call, kind);
            }
        }
        return selected == null
                ? Optional.empty()
                : Optional.of(new InteractiveStepPlan(manifest.calls(), selected));
    }

    public record SelectedControl(ToolCallIntent call, CallKind kind) {
        public SelectedControl {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(kind, "kind");
            if (!kind.isInteractive()) {
                throw new IllegalArgumentException("selected control must be interactive");
            }
        }

        public int providerOrdinal() {
            return call.providerOrdinal();
        }
    }

    /**
     * Immutable interactive plan. It intentionally has no dispatch list: no Tool call in this
     * response, including the selected control, is executable until the control is resolved.
     */
    public record InteractiveStepPlan(
            List<ToolCallIntent> calls, SelectedControl selectedControl) {

        private static final Set<String> RESULT_FIELDS = Set.of(
                "type", "tool_use_id", "content", "is_error");
        private static final Set<String> RESULT_FIELDS_WITH_ERROR_TYPE = Set.of(
                "type", "tool_use_id", "content", "is_error", "error_type");

        public InteractiveStepPlan {
            calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
            Objects.requireNonNull(selectedControl, "selectedControl");
            validateCalls(calls, selectedControl);
        }

        public List<ToolCallIntent> executionCandidatesBeforeResolution() {
            return List.of();
        }

        public boolean shouldExecuteBeforeResolution(int providerOrdinal) {
            requireKnownOrdinal(providerOrdinal);
            return false;
        }

        /**
         * Builds the exact N-element result vector expected by the durable result transaction.
         */
        public List<MessageSnapshot> completeResultVector(MessageSnapshot selectedResult) {
            validateSelectedResult(selectedResult, selectedControl.call().toolUseId());

            List<MessageSnapshot> results = new ArrayList<>(calls.size());
            for (ToolCallIntent call : calls) {
                if (call.providerOrdinal() == selectedControl.providerOrdinal()) {
                    results.add(selectedResult);
                } else {
                    results.add(MessageSnapshot.capture(Message.toolResult(
                            call.toolUseId(),
                            ABORTED_RESULT_CONTENT,
                            true,
                            ABORTED_RESULT_ERROR_TYPE)));
                }
            }
            return List.copyOf(results);
        }

        private void requireKnownOrdinal(int providerOrdinal) {
            if (providerOrdinal < 0 || providerOrdinal >= calls.size()
                    || calls.get(providerOrdinal).providerOrdinal() != providerOrdinal) {
                throw new IllegalArgumentException("unknown providerOrdinal");
            }
        }

        private static void validateCalls(
                List<ToolCallIntent> calls, SelectedControl selectedControl) {
            if (calls.isEmpty()) {
                throw new IllegalArgumentException("interactive plan calls must not be empty");
            }
            for (int ordinal = 0; ordinal < calls.size(); ordinal++) {
                ToolCallIntent call = Objects.requireNonNull(calls.get(ordinal), "call");
                if (call.providerOrdinal() != ordinal) {
                    throw new IllegalArgumentException(
                            "interactive plan ordinals must be contiguous from zero");
                }
            }
            int selectedOrdinal = selectedControl.providerOrdinal();
            if (selectedOrdinal < 0 || selectedOrdinal >= calls.size()
                    || !calls.get(selectedOrdinal).equals(selectedControl.call())) {
                throw new IllegalArgumentException("selected control must belong to calls");
            }
        }

        private static void validateSelectedResult(
                MessageSnapshot selectedResult, String selectedToolUseId) {
            if (selectedResult == null
                    || selectedResult.role() != Message.Role.USER
                    || selectedResult.reasoningContent() != null) {
                throw invalidSelectedResult();
            }
            Object content = selectedResult.content().toJavaValue();
            if (!(content instanceof List<?> blocks)
                    || blocks.size() != 1
                    || !(blocks.get(0) instanceof Map<?, ?> block)
                    || (!block.keySet().equals(RESULT_FIELDS)
                        && !block.keySet().equals(RESULT_FIELDS_WITH_ERROR_TYPE))
                    || !"tool_result".equals(block.get("type"))
                    || !selectedToolUseId.equals(block.get("tool_use_id"))
                    || !(block.get("content") instanceof String)
                    || !(block.get("is_error") instanceof Boolean isError)) {
                throw invalidSelectedResult();
            }
            if (block.containsKey("error_type")
                    && (!isError
                        || !(block.get("error_type") instanceof String errorType)
                        || errorType.isBlank())) {
                throw invalidSelectedResult();
            }
        }

        private static IllegalArgumentException invalidSelectedResult() {
            return new IllegalArgumentException(
                    "selectedResult must target the selected Tool call");
        }
    }
}
