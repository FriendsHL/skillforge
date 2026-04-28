package com.skillforge.core.engine;

/**
 * Plan r2 §7 — telemetry callback.
 * <p>Server 提供实现（{@code SkillService.recordUsage(name, success, errorType)}），
 * 在 AgentLoopEngine.executeToolCall 的所有 return 路径前调用。
 * <p>实现必须吞掉自身异常 / 超时 — telemetry 失败不能让 tool 调用失败。
 */
@FunctionalInterface
public interface SkillTelemetryRecorder {

    /**
     * @param skillName tool / skill 名（可能是内置 Tool 名 — 实现端按需 noop）
     * @param success   是否成功
     * @param errorType 失败时的分类：{@code VALIDATION / EXECUTION / NOT_ALLOWED}；
     *                  成功时为 null
     */
    void record(String skillName, boolean success, String errorType);
}
