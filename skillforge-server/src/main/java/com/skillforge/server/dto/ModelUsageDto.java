package com.skillforge.server.dto;

public class ModelUsageDto {

    private String model;
    private long totalTokens;

    public ModelUsageDto() {
    }

    public ModelUsageDto(String model, long totalTokens) {
        this.model = model;
        this.totalTokens = totalTokens;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }
}
