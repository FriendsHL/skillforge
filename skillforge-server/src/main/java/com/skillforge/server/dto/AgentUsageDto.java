package com.skillforge.server.dto;

public class AgentUsageDto {

    private String agentName;
    private long totalTokens;

    public AgentUsageDto() {
    }

    public AgentUsageDto(String agentName, long totalTokens) {
        this.agentName = agentName;
        this.totalTokens = totalTokens;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public long getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(long totalTokens) {
        this.totalTokens = totalTokens;
    }
}
