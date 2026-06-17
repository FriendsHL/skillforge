package com.skillforge.server.mcp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * P11 MCP-CLIENT: one row per externally-spawned MCP stdio server.
 *
 * <p>{@link #args} and {@link #env} are stored as TEXT holding canonical JSON
 * (matches project convention — see {@code EvalScenarioEntity} comment and
 * {@code ScheduledTaskEntity.channelTarget}). The service layer parses /
 * serializes via the Spring-managed {@code ObjectMapper}.
 */
@Entity
@Table(name = "t_mcp_server")
@EntityListeners(AuditingEntityListener.class)
public class McpServerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stable identifier; used as {@code <server>} in {@code mcp_<server>_<tool>}
     * registration. DB CHECK constraint enforces {@code [a-z0-9_]+} length<=32.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String name;

    /**
     * Transport kind: {@code "stdio"} (subprocess NDJSON) or {@code "http"}
     * (Streamable HTTP POST). Immutable post-create (service layer enforces).
     * DB CHECK pairs it with command/url presence (V152).
     */
    @Column(nullable = false, length = 16)
    private String transport = "stdio";

    /**
     * Process command for stdio transport. Nullable since V152 — an http server has
     * no command (the DB CHECK requires command for stdio, url for http).
     */
    @Column(length = 256)
    private String command;

    /** Endpoint URL for http transport (null for stdio). */
    @Column(columnDefinition = "TEXT")
    private String url;

    /**
     * JSON object of HTTP headers for http transport; values may use
     * {@code ${ENV_VAR_NAME}} placeholders resolved by the lifecycle layer at
     * connect time (same semantics as {@link #env}). Defaults to {@code "{}"}.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String headers = "{}";

    /** JSON array of process args, e.g. {@code ["-y", "@modelcontextprotocol/server-time"]}. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String args = "[]";

    /**
     * JSON object of env vars; values may use {@code ${ENV_VAR_NAME}} placeholders
     * resolved by the lifecycle layer at process spawn time (INV-5).
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String env = "{}";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public McpServerEntity() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTransport() { return transport; }
    public void setTransport(String transport) { this.transport = transport != null ? transport : "stdio"; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers != null ? headers : "{}"; }

    public String getArgs() { return args; }
    public void setArgs(String args) { this.args = args != null ? args : "[]"; }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env != null ? env : "{}"; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
