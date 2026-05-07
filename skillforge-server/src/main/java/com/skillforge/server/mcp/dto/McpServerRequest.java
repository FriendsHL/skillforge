package com.skillforge.server.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for create / update on {@code /api/mcp-servers}. Args + env are
 * passed in their natural shape (List/Map) — controller serializes to canonical
 * JSON before persisting.
 *
 * <p>For PUT-style partial update, controller checks each field for null;
 * fields the caller did not include stay untouched.
 */
public class McpServerRequest {

    private String name;
    private String command;
    private List<String> args;
    private Map<String, String> env;
    private String description;
    private Boolean enabled;

    public McpServerRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
