package com.skillforge.server.context;

import com.skillforge.core.context.ContextProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides runtime environment information as context for the system prompt.
 */
@Component
public class EnvironmentContextProvider implements ContextProvider {

    @Override
    public String getName() {
        return "Environment";
    }

    @Override
    public Map<String, String> getContext() {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("working_directory", System.getProperty("user.dir"));
        ctx.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        ctx.put("java_version", System.getProperty("java.version"));
        ctx.put("current_date", LocalDate.now().toString());
        // Note: current_time removed — second-level precision would break prompt cache across sessions
        return ctx;
    }
}
