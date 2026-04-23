package com.skillforge.core.skill;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Skill 执行结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillResult {

    /**
     * 错误类型。VALIDATION 表示 LLM 生成的工具入参缺失/不合法（应触发结构化重试，
     * 不应被 detectWaste 当作 execution failure 计入 consecutive error，
     * 否则会触发不必要的 compaction 把上下文越压越小、形成正反馈）。
     * EXECUTION 表示 skill 执行阶段的真实失败（IO、外部系统、业务规则）。
     */
    public enum ErrorType {
        VALIDATION,
        EXECUTION
    }

    private boolean success;
    private String output;
    private String error;
    private ErrorType errorType;

    public SkillResult() {
    }

    private SkillResult(boolean success, String output, String error, ErrorType errorType) {
        this.success = success;
        this.output = output;
        this.error = error;
        this.errorType = errorType;
    }

    /**
     * 创建成功结果。
     */
    public static SkillResult success(String output) {
        return new SkillResult(true, output, null, null);
    }

    /**
     * 创建失败结果（默认 EXECUTION 类）。
     */
    public static SkillResult error(String message) {
        return new SkillResult(false, null, message, ErrorType.EXECUTION);
    }

    /**
     * 创建 LLM 入参验证失败结果。用于必填字段缺失、类型错误等"LLM 应重试"场景。
     */
    public static SkillResult validationError(String message) {
        return new SkillResult(false, null, message, ErrorType.VALIDATION);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public ErrorType getErrorType() {
        return errorType;
    }

    public void setErrorType(ErrorType errorType) {
        this.errorType = errorType;
    }
}
