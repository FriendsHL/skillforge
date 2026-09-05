package com.skillforge.server.config;

import com.skillforge.core.llm.ModelConfig;
import com.skillforge.server.controller.LlmModelsController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BailianTokenPlanConfigurationTest {

    @Test
    void productionConfigUsesSubscriptionEndpointAndExposesQwenAndDeepseekCapabilities() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load(
                "production", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        LlmProperties properties = Binder.get(environment)
                .bind("skillforge.llm", Bindable.of(LlmProperties.class)).get();
        var tokenPlan = properties.getProviders().get("bailian-token-plan");
        assertThat(tokenPlan).isNotNull();
        assertThat(tokenPlan.getType()).isEqualTo("openai");
        assertThat(tokenPlan.getBaseUrl() + tokenPlan.getChatPath()).isEqualTo(
                "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions");
        assertThat(tokenPlan.getContextWindowTokens()).isEqualTo(1_000_000);
        assertThat(ModelConfig.lookupKnownContextWindow(tokenPlan.getModel())).contains(1_000_000);
        assertThat(properties.supportsVision("bailian-token-plan:qwen3.8-max")).isTrue();
        assertThat(properties.supportsVision("bailian:qwen3.8-max")).isFalse();
        assertThat(properties.getProviders().get("bailian").getBaseUrl())
                .isEqualTo("https://coding.dashscope.aliyuncs.com");

        assertThat(tokenPlan.getModels()).containsExactly("qwen3.8-max", "qwen3.7-max", "deepseek-v4-pro", "deepseek-v4-pro-0813", "deepseek-v4-flash-0731");
        assertThat(properties.supportsVision("bailian-token-plan:qwen3.7-max")).isFalse();
        assertThat(properties.supportsVision("bailian-token-plan:deepseek-v4-pro-0813")).isFalse();

        var mvc = MockMvcBuilders.standaloneSetup(new LlmModelsController(properties)).build();
        for (String name : java.util.List.of("qwen3.7-max", "deepseek-v4-pro", "deepseek-v4-pro-0813", "deepseek-v4-flash-0731")) {
            String path = "$[?(@.id == 'bailian-token-plan:" + name + "')]";
            mvc.perform(get("/api/llm/models"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(path + ".model").value(org.hamcrest.Matchers.contains(name)))
                    .andExpect(jsonPath(path + ".supportsThinking").value(org.hamcrest.Matchers.contains(true)))
                    .andExpect(jsonPath(path + ".supportsReasoningEffort")
                            .value(org.hamcrest.Matchers.contains(name.startsWith("deepseek"))))
                    .andExpect(jsonPath(path + ".protocolFamily")
                            .value(org.hamcrest.Matchers.contains(name.startsWith("deepseek") ? "deepseek_v4" : "qwen_dashscope")))
                    .andExpect(jsonPath(path + ".supportsVision").value(org.hamcrest.Matchers.contains(false)));
        }
        String model = "$[?(@.id == 'bailian-token-plan:qwen3.8-max')]";
        mvc.perform(get("/api/llm/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(model + ".model").value(org.hamcrest.Matchers.contains("qwen3.8-max")))
                .andExpect(jsonPath(model + ".supportsThinking").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath(model + ".supportsVision").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath(model + ".protocolFamily").value(org.hamcrest.Matchers.contains("qwen_dashscope")));
    }
}
