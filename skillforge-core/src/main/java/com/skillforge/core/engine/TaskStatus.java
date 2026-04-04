package com.skillforge.core.engine;

/**
 * 子 Agent 任务状态。
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    PENDING_CONFIRM,
    COMPLETED,
    FAILED,
    CANCELLED
}
