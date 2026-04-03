package com.skillforge.server.dto;

public class DashboardOverview {

    private long totalAgents;
    private long activeAgents;
    private long totalSessions;
    private long todaySessions;
    private long totalInputTokens;
    private long totalOutputTokens;

    public DashboardOverview() {
    }

    public long getTotalAgents() {
        return totalAgents;
    }

    public void setTotalAgents(long totalAgents) {
        this.totalAgents = totalAgents;
    }

    public long getActiveAgents() {
        return activeAgents;
    }

    public void setActiveAgents(long activeAgents) {
        this.activeAgents = activeAgents;
    }

    public long getTotalSessions() {
        return totalSessions;
    }

    public void setTotalSessions(long totalSessions) {
        this.totalSessions = totalSessions;
    }

    public long getTodaySessions() {
        return todaySessions;
    }

    public void setTodaySessions(long todaySessions) {
        this.todaySessions = todaySessions;
    }

    public long getTotalInputTokens() {
        return totalInputTokens;
    }

    public void setTotalInputTokens(long totalInputTokens) {
        this.totalInputTokens = totalInputTokens;
    }

    public long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public void setTotalOutputTokens(long totalOutputTokens) {
        this.totalOutputTokens = totalOutputTokens;
    }
}
