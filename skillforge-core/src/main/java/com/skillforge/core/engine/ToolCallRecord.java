package com.skillforge.core.engine;

import java.util.Map;

/**
 * 工具调用记录，用于追踪每次 Skill 调用的详细信息。
 */
public class ToolCallRecord {

    private String skillName;
    private Map<String, Object> input;
    private String output;
    private boolean success;
    private long durationMs;
    private long timestamp;

    public ToolCallRecord() {
    }

    public ToolCallRecord(String skillName, Map<String, Object> input, String output,
                          boolean success, long durationMs, long timestamp) {
        this.skillName = skillName;
        this.input = input;
        this.output = output;
        this.success = success;
        this.durationMs = durationMs;
        this.timestamp = timestamp;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
