package com.skillforge.core.skill;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Skill 执行结果。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SkillResult {

    private boolean success;
    private String output;
    private String error;

    public SkillResult() {
    }

    private SkillResult(boolean success, String output, String error) {
        this.success = success;
        this.output = output;
        this.error = error;
    }

    /**
     * 创建成功结果。
     */
    public static SkillResult success(String output) {
        return new SkillResult(true, output, null);
    }

    /**
     * 创建失败结果。
     */
    public static SkillResult error(String message) {
        return new SkillResult(false, null, message);
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
}
