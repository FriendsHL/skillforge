package com.skillforge.server.dto;

public class DailyUsageDto {

    private String date;
    private long inputTokens;
    private long outputTokens;

    public DailyUsageDto() {
    }

    public DailyUsageDto(String date, long inputTokens, long outputTokens) {
        this.date = date;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(long inputTokens) {
        this.inputTokens = inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(long outputTokens) {
        this.outputTokens = outputTokens;
    }
}
