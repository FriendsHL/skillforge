package com.skillforge.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static metadata endpoints for the lifecycle-hook editor UI.
 *
 * <ul>
 *   <li>{@code GET /api/lifecycle-hooks/events} — 5-event schema + display metadata.</li>
 *   <li>{@code GET /api/lifecycle-hooks/presets} — 4 built-in templates the Preset UI renders.</li>
 * </ul>
 *
 * <p>Both payloads are hard-coded server-side so the frontend stays a thin consumer.
 *
 * <p>Auth: protected by the global {@link com.skillforge.server.config.AuthInterceptor},
 * which requires a Bearer token on every {@code /api/**} request except {@code /api/auth/**}.
 * Unauthenticated calls receive 401.
 */
@RestController
@RequestMapping("/api/lifecycle-hooks")
public class LifecycleHookController {

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> listEvents() {
        List<Map<String, Object>> events = List.of(
                eventMeta("SessionStart",
                        "会话开始",
                        "用户发送第一条消息时触发（每个 session 只触发一次）",
                        Map.of("agent_name", "string", "user_id", "long"),
                        true,
                        "Recommend async=true for non-critical auditing; use sync for bootstrap flows"),
                eventMeta("UserPromptSubmit",
                        "用户 Prompt 提交",
                        "每一轮 Agent Loop 开始时触发（LLM 调用前）",
                        Map.of("user_message", "string", "message_count", "int"),
                        true,
                        "Set failurePolicy=ABORT to block sensitive prompts before LLM sees them"),
                eventMeta("PostToolUse",
                        "工具调用后",
                        "每次 Skill/Tool 执行完后触发（ABORT 无效，仅记录）",
                        Map.of("skill_name", "string",
                                "skill_input", "object",
                                "skill_output", "string",
                                "success", "bool",
                                "duration_ms", "long"),
                        false,
                        "Useful for tool-call audit / result cleansing"),
                eventMeta("Stop",
                        "Agent Loop 结束",
                        "Agent Loop 正常结束 / 达到 maxLoops / LLM 返回 end_turn 时触发",
                        Map.of("loop_count", "int",
                                "total_input_tokens", "long",
                                "total_output_tokens", "long",
                                "final_response", "string"),
                        false,
                        "Good place to emit metrics or summarize one turn"),
                eventMeta("SessionEnd",
                        "会话结束",
                        "Session 完成 / 取消 / 错误终止时触发（异步执行）",
                        Map.of("user_id", "long", "message_count", "int", "reason", "string"),
                        false,
                        "Recommend async=true; common use case: push summary to Notion/飞书/邮件")
        );
        return ResponseEntity.ok(Map.of("version", "1.0", "events", events));
    }

    @GetMapping("/presets")
    public ResponseEntity<Map<String, Object>> listPresets() {
        List<Map<String, Object>> presets = List.of(
                auditAllPreset(),
                promptEnricherPreset(),
                memoryWriterPreset(),
                fullLifecyclePreset()
        );
        return ResponseEntity.ok(Map.of("version", "1.0", "presets", presets));
    }

    // ----- event / preset builders -----

    private static Map<String, Object> eventMeta(String id, String displayName, String description,
                                                 Map<String, String> inputSchema, boolean canAbort,
                                                 String recommendation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("displayName", displayName);
        m.put("description", description);
        m.put("inputSchema", inputSchema);
        m.put("canAbort", canAbort);
        m.put("recommendation", recommendation);
        return m;
    }

    private static Map<String, Object> auditAllPreset() {
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("id", "audit-all");
        preset.put("name", "Audit All");
        preset.put("description", "5 个事件都绑定审计 Skill（记录所有交互到 Memory），适合合规与调试场景");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", 1);
        Map<String, Object> hooks = new LinkedHashMap<>();
        for (String ev : List.of("SessionStart", "UserPromptSubmit", "PostToolUse", "Stop", "SessionEnd")) {
            hooks.put(ev, List.of(hookEntry(skillHandler("Memory", null),
                    ev.equals("SessionEnd") || ev.equals("SessionStart") ? 30 : 10,
                    "CONTINUE", true, "审计：" + ev)));
        }
        config.put("hooks", hooks);
        preset.put("config", config);
        return preset;
    }

    private static Map<String, Object> promptEnricherPreset() {
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("id", "prompt-enricher");
        preset.put("name", "Prompt Enricher");
        preset.put("description", "仅 UserPromptSubmit 绑定 Memory 读取 Skill，在 LLM 看到 prompt 前注入上下文");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", 1);
        Map<String, Object> hooks = new LinkedHashMap<>();
        hooks.put("UserPromptSubmit",
                List.of(hookEntry(skillHandler("MemorySearch", Map.of("limit", 5)),
                        5, "CONTINUE", false, "注入相关记忆")));
        config.put("hooks", hooks);
        preset.put("config", config);
        return preset;
    }

    private static Map<String, Object> memoryWriterPreset() {
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("id", "memory-writer");
        preset.put("name", "Memory Writer");
        preset.put("description", "仅 SessionEnd 绑定 Memory 写入（async=true）— 对话结束后异步保存摘要");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", 1);
        Map<String, Object> hooks = new LinkedHashMap<>();
        hooks.put("SessionEnd",
                List.of(hookEntry(skillHandler("Memory", Map.of("op", "write")),
                        60, "CONTINUE", true, "写入会话摘要")));
        config.put("hooks", hooks);
        preset.put("config", config);
        return preset;
    }

    private static Map<String, Object> fullLifecyclePreset() {
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("id", "full-lifecycle");
        preset.put("name", "Full Lifecycle");
        preset.put("description", "SessionStart / Stop / SessionEnd 各绑不同 Skill，演示完整生命周期编排");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", 1);
        Map<String, Object> hooks = new LinkedHashMap<>();
        hooks.put("SessionStart",
                List.of(hookEntry(skillHandler("MemorySearch", Map.of("limit", 3)),
                        10, "CONTINUE", true, "加载上下文")));
        hooks.put("Stop",
                List.of(hookEntry(skillHandler("Memory", Map.of("op", "note")),
                        15, "CONTINUE", true, "记录本轮")));
        hooks.put("SessionEnd",
                List.of(hookEntry(skillHandler("Memory", Map.of("op", "summarize")),
                        60, "CONTINUE", true, "会话总结")));
        config.put("hooks", hooks);
        preset.put("config", config);
        return preset;
    }

    private static Map<String, Object> hookEntry(Map<String, Object> handler, int timeoutSeconds,
                                                 String failurePolicy, boolean async, String displayName) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("handler", handler);
        entry.put("timeoutSeconds", timeoutSeconds);
        entry.put("failurePolicy", failurePolicy);
        entry.put("async", async);
        entry.put("displayName", displayName);
        return entry;
    }

    private static Map<String, Object> skillHandler(String skillName, Map<String, Object> args) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("type", "skill");
        h.put("skillName", skillName);
        if (args != null && !args.isEmpty()) h.put("args", args);
        return h;
    }
}
