package com.skillforge.server.hook.method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.core.engine.hook.HookEvent;
import com.skillforge.core.engine.hook.HookExecutionContext;
import com.skillforge.core.engine.hook.HookRunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FeishuNotifyMethod} — focuses on input validation and SSRF checks.
 * Actual HTTP calls are not tested here (would require a mock HTTP server).
 */
class FeishuNotifyMethodTest {

    private FeishuNotifyMethod method;

    private static final HookExecutionContext CTX = new HookExecutionContext(
            "sess-1", 7L, HookEvent.STOP,
            Map.of("_hook_origin", "lifecycle:Stop"));

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        method = new FeishuNotifyMethod(om);
    }

    @Test
    @DisplayName("ref returns builtin.feishu.notify")
    void ref_returnsExpected() {
        assertThat(method.ref()).isEqualTo("builtin.feishu.notify");
    }

    @Test
    @DisplayName("execute rejects missing webhook_url")
    void execute_missingWebhookUrl_returnsFailure() {
        HookRunResult result = method.execute(Map.of(), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("webhook_url is required");
    }

    @Test
    @DisplayName("execute rejects localhost webhook URL (SSRF)")
    void execute_localhostUrl_blocked() {
        HookRunResult result = method.execute(
                Map.of("webhook_url", "http://localhost:8080/hook"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("ssrf_blocked");
    }

    @Test
    @DisplayName("execute rejects private IP webhook URL (SSRF)")
    void execute_privateIpUrl_blocked() {
        HookRunResult result = method.execute(
                Map.of("webhook_url", "http://10.0.0.1/hook"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("ssrf_blocked");
    }

    @Test
    @DisplayName("execute rejects file:// scheme webhook URL")
    void execute_fileScheme_blocked() {
        HookRunResult result = method.execute(
                Map.of("webhook_url", "file:///etc/passwd"), CTX);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("ssrf_blocked");
    }

    @Test
    @DisplayName("execute allows open.feishu.cn domain (passes SSRF check)")
    void execute_feishuDomain_allowed() {
        // This test will fail at the HTTP level (no real server), but should pass SSRF validation
        HookRunResult result = method.execute(
                Map.of("webhook_url", "https://open.feishu.cn/open-apis/bot/v2/hook/test-token"), CTX);

        // Will fail at HTTP level since there's no real server, but the error should NOT be ssrf_blocked
        String error = result.errorMessage();
        if (error != null) {
            assertThat(error).doesNotContain("ssrf_blocked");
        }
        // If errorMessage is null, it means the request succeeded or SSRF was not the failure mode — either way, passes.
    }

    @Test
    @DisplayName("execute allows open.larksuite.com domain (passes SSRF check)")
    void execute_larksuiteDomain_allowed() {
        HookRunResult result = method.execute(
                Map.of("webhook_url", "https://open.larksuite.com/open-apis/bot/v2/hook/test-token"), CTX);

        String error = result.errorMessage();
        if (error != null) {
            assertThat(error).doesNotContain("ssrf_blocked");
        }
    }
}
