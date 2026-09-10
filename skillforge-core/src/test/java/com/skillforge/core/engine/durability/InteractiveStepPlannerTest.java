package com.skillforge.core.engine.durability;

import com.skillforge.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractiveStepPlannerTest {

    private final InteractiveStepPlanner planner = new InteractiveStepPlanner();

    @Test
    void plan_normalThenAsk_selectsAskAndAbortsEarlierSibling() {
        ToolCallManifest manifest = manifest(
                call(0, "normal-0", "ReadFile"),
                call(1, "ask-1", "ask_user"));

        InteractiveStepPlanner.InteractiveStepPlan plan = requirePlan(manifest);
        List<MessageSnapshot> results = plan.completeResultVector(
                result("ask-1", "selected answer", false, null));

        assertSelected(plan, 1, "ask-1", InteractiveStepPlanner.CallKind.ASK_USER);
        assertNoExecutionBeforeResolution(plan);
        assertAborted(results.get(0), "normal-0");
        assertResult(results.get(1), "ask-1", "selected answer", false, null);
    }

    @Test
    void plan_askThenNormal_selectsAskAndAbortsLaterSibling() {
        ToolCallManifest manifest = manifest(
                call(0, "ask-0", "ask_user"),
                call(1, "normal-1", "ReadFile"));

        InteractiveStepPlanner.InteractiveStepPlan plan = requirePlan(manifest);
        List<MessageSnapshot> results = plan.completeResultVector(
                result("ask-0", "selected answer", false, null));

        assertSelected(plan, 0, "ask-0", InteractiveStepPlanner.CallKind.ASK_USER);
        assertNoExecutionBeforeResolution(plan);
        assertResult(results.get(0), "ask-0", "selected answer", false, null);
        assertAborted(results.get(1), "normal-1");
    }

    @Test
    void plan_normalConfirmationNormal_selectsConfirmationAndAbortsBothSiblings() {
        ToolCallManifest manifest = manifest(
                call(0, "normal-0", "ReadFile"),
                call(1, "confirm-1", "ConfirmMutation"),
                call(2, "normal-2", "ListFiles"));

        InteractiveStepPlanner.InteractiveStepPlan plan = requirePlan(manifest);
        List<MessageSnapshot> results = plan.completeResultVector(
                result("confirm-1", "approved", false, null));

        assertSelected(plan, 1, "confirm-1", InteractiveStepPlanner.CallKind.CONFIRMATION);
        assertNoExecutionBeforeResolution(plan);
        assertThat(results).hasSize(3);
        assertAborted(results.get(0), "normal-0");
        assertResult(results.get(1), "confirm-1", "approved", false, null);
        assertAborted(results.get(2), "normal-2");
    }

    @Test
    void plan_multipleInteractive_selectsEarliestProviderOrdinalAndAbortsEveryOtherCall() {
        ToolCallManifest manifest = manifest(
                call(0, "normal-0", "ReadFile"),
                call(1, "confirm-1", "ConfirmMutation"),
                call(2, "ask-2", "ask_user"),
                call(3, "confirm-3", "ConfirmMutation"));

        InteractiveStepPlanner.InteractiveStepPlan plan = requirePlan(manifest);
        List<MessageSnapshot> results = plan.completeResultVector(
                result("confirm-1", "denied", true, "EXECUTION"));

        assertSelected(plan, 1, "confirm-1", InteractiveStepPlanner.CallKind.CONFIRMATION);
        assertNoExecutionBeforeResolution(plan);
        assertThat(results).hasSize(4);
        assertAborted(results.get(0), "normal-0");
        assertResult(results.get(1), "confirm-1", "denied", true, "EXECUTION");
        assertAborted(results.get(2), "ask-2");
        assertAborted(results.get(3), "confirm-3");
    }

    @Test
    void plan_interactiveFoundEarly_stillPreflightsTheCompleteProviderVector() {
        ToolCallManifest manifest = manifest(
                call(0, "ask-0", "ask_user"),
                call(1, "normal-1", "ReadFile"),
                call(2, "confirm-2", "ConfirmMutation"));
        List<Integer> classifiedOrdinals = new ArrayList<>();

        Optional<InteractiveStepPlanner.InteractiveStepPlan> result = planner.plan(
                manifest,
                call -> {
                    classifiedOrdinals.add(call.providerOrdinal());
                    return classify(call);
                });

        assertThat(result).isPresent();
        assertThat(classifiedOrdinals).containsExactly(0, 1, 2);
    }

    @Test
    void plan_noInteractiveCall_returnsEmpty() {
        ToolCallManifest manifest = manifest(
                call(0, "normal-0", "ReadFile"),
                call(1, "normal-1", "ListFiles"));

        assertThat(planner.plan(manifest, this::classify)).isEmpty();
    }

    @Test
    void completeResultVector_selectedResultTargetsSibling_rejectsInsteadOfMisordering() {
        ToolCallManifest manifest = manifest(
                call(0, "ask-0", "ask_user"),
                call(1, "normal-1", "ReadFile"));
        InteractiveStepPlanner.InteractiveStepPlan plan = requirePlan(manifest);

        assertThatThrownBy(() -> plan.completeResultVector(
                result("normal-1", "wrong target", false, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selectedResult must target the selected Tool call");
    }

    @Test
    void completeResultVector_selectedResultIsNotACompleteToolResult_rejects() {
        ToolCallManifest manifest = manifest(call(0, "ask-0", "ask_user"));
        InteractiveStepPlanner.InteractiveStepPlan plan = requirePlan(manifest);
        MessageSnapshot missingErrorFlag = new MessageSnapshot(
                Message.Role.USER,
                FrozenJson.capture(List.of(Map.of(
                        "type", "tool_result",
                        "tool_use_id", "ask-0",
                        "content", "answer"))),
                null);

        assertThatThrownBy(() -> plan.completeResultVector(missingErrorFlag))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("selectedResult must target the selected Tool call");
    }

    private InteractiveStepPlanner.InteractiveStepPlan requirePlan(ToolCallManifest manifest) {
        Optional<InteractiveStepPlanner.InteractiveStepPlan> plan = planner.plan(manifest, this::classify);
        return plan.orElseThrow();
    }

    private InteractiveStepPlanner.CallKind classify(ToolCallIntent call) {
        return switch (call.toolName()) {
            case "ask_user" -> InteractiveStepPlanner.CallKind.ASK_USER;
            case "ConfirmMutation" -> InteractiveStepPlanner.CallKind.CONFIRMATION;
            default -> InteractiveStepPlanner.CallKind.NORMAL;
        };
    }

    private static void assertSelected(
            InteractiveStepPlanner.InteractiveStepPlan plan,
            int ordinal,
            String toolUseId,
            InteractiveStepPlanner.CallKind kind) {
        assertThat(plan.selectedControl().providerOrdinal()).isEqualTo(ordinal);
        assertThat(plan.selectedControl().call().toolUseId()).isEqualTo(toolUseId);
        assertThat(plan.selectedControl().kind()).isEqualTo(kind);
    }

    private static void assertNoExecutionBeforeResolution(
            InteractiveStepPlanner.InteractiveStepPlan plan) {
        assertThat(plan.executionCandidatesBeforeResolution()).isEmpty();
        assertThat(plan.calls())
                .allSatisfy(call -> assertThat(plan.shouldExecuteBeforeResolution(
                        call.providerOrdinal())).isFalse());
    }

    private static void assertAborted(MessageSnapshot snapshot, String toolUseId) {
        assertResult(
                snapshot,
                toolUseId,
                InteractiveStepPlanner.ABORTED_RESULT_CONTENT,
                true,
                InteractiveStepPlanner.ABORTED_RESULT_ERROR_TYPE);
    }

    @SuppressWarnings("unchecked")
    private static void assertResult(
            MessageSnapshot snapshot,
            String toolUseId,
            String content,
            boolean error,
            String errorType) {
        Message message = snapshot.toMessage();
        assertThat(message.getRole()).isEqualTo(Message.Role.USER);
        assertThat(message.getContent()).isInstanceOf(List.class);
        List<Object> blocks = (List<Object>) message.getContent();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(Map.class);
        Map<String, Object> block = (Map<String, Object>) blocks.get(0);
        assertThat(block)
                .containsEntry("type", "tool_result")
                .containsEntry("tool_use_id", toolUseId)
                .containsEntry("content", content)
                .containsEntry("is_error", error);
        if (errorType == null) {
            assertThat(block).doesNotContainKey("error_type");
        } else {
            assertThat(block).containsEntry("error_type", errorType);
        }
    }

    private static MessageSnapshot result(
            String toolUseId, String content, boolean error, String errorType) {
        return MessageSnapshot.capture(Message.toolResult(toolUseId, content, error, errorType));
    }

    private static ToolCallManifest manifest(ToolCallIntent... calls) {
        List<ToolCallIntent> ordered = List.of(calls);
        ReplaySafety aggregate = ReplaySafety.aggregate(
                ordered.stream().map(ToolCallIntent::replaySafety).toList());
        return new ToolCallManifest(ordered, aggregate);
    }

    private static ToolCallIntent call(int ordinal, String toolUseId, String toolName) {
        return new ToolCallIntent(
                ordinal,
                toolUseId,
                toolName,
                FrozenJson.capture(Map.of()),
                ReplaySafety.READ_ONLY_REPLAYABLE);
    }
}
