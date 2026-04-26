package com.skillforge.core.engine.confirm;

/**
 * SPI:推送 human-confirmation prompt 给用户, 阻塞等待 Decision.
 *
 * <p>严禁在 {@code SkillHook} 内调用 —— 仅
 * {@link com.skillforge.core.engine.AgentLoopEngine#handleInstallConfirmation
 * AgentLoopEngine main-thread confirmation branches} 允许调用.
 * 原因:engine 主线程分支才有 1:1 tool_use↔tool_result 守恒,SkillHook 走 supplyAsync
 * 会遇到 120s allOf timeout.
 */
public interface ConfirmationPrompter {

    /**
     * Synchronous prompt — blocks the engine's main loop thread until the user responds
     * or the timeout fires.
     *
     * @return {@link Decision#APPROVED} / {@link Decision#DENIED} / {@link Decision#TIMEOUT}
     *         — never {@code null}
     * @throws ChannelUnavailableException if no channel can deliver the prompt
     *         (caller converts to error tool_result)
     */
    Decision prompt(ConfirmationRequest request);

    /**
     * Input to {@link #prompt}. Field names retain the original install-confirmation naming
     * for wire compatibility; non-install tools may pass their operation and target through
     * {@code installTool}/{@code installTarget}. {@code triggererOpenId} is only populated
     * for feishu turns (captured by {@code ChannelSessionRouter} at message ingress).
     */
    record ConfirmationRequest(
            String sessionId,
            Long userId,
            String toolUseId,
            String installTool,
            String installTarget,
            String command,
            String triggererOpenId,
            long timeoutSeconds
    ) {}
}
