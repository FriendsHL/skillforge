package com.skillforge.cli;

import java.io.File;
import java.util.Map;

/**
 * Resolves {@code server URL} and {@code userId} from, in precedence order:
 * 1. CLI flag, 2. environment variable, 3. {@code ~/.skillforge/config.yaml}, 4. defaults.
 */
public class CliConfig {

    public static final String DEFAULT_SERVER = "http://localhost:8080";
    public static final long DEFAULT_USER_ID = 1L;

    private final String server;
    private final long userId;

    public CliConfig(String server, long userId) {
        this.server = server;
        this.userId = userId;
    }

    public String getServer() { return server; }
    public long getUserId() { return userId; }

    public static CliConfig resolve(String cliServer, Long cliUserId) {
        String server = cliServer;
        Long userId = cliUserId;

        // 2. env
        if (server == null) {
            String env = System.getenv("SKILLFORGE_SERVER");
            if (env != null && !env.isBlank()) server = env.trim();
        }
        if (userId == null) {
            String env = System.getenv("SKILLFORGE_USER_ID");
            if (env != null && !env.isBlank()) {
                try { userId = Long.parseLong(env.trim()); } catch (NumberFormatException ignored) {}
            }
        }

        // 3. config file
        if (server == null || userId == null) {
            File f = new File(System.getProperty("user.home"), ".skillforge/config.yaml");
            if (f.exists() && f.isFile()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = YamlMapper.yaml().readValue(f, Map.class);
                    if (server == null && m.get("server") != null) {
                        server = m.get("server").toString();
                    }
                    if (userId == null && m.get("userId") != null) {
                        userId = Long.parseLong(m.get("userId").toString());
                    }
                } catch (Exception e) {
                    System.err.println("[warn] Failed to parse ~/.skillforge/config.yaml: " + e.getMessage());
                }
            }
        }

        // 4. defaults
        if (server == null) server = DEFAULT_SERVER;
        if (userId == null) userId = DEFAULT_USER_ID;

        // strip trailing slash
        if (server.endsWith("/")) server = server.substring(0, server.length() - 1);

        return new CliConfig(server, userId);
    }
}
