package com.skillforge.server.controller;

import com.skillforge.server.config.LlmProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Exposes the configured LLM providers/models so the frontend no longer needs a
 * hardcoded MODEL_OPTIONS list. The id and label use the {@code <provider>:<model>}
 * format the frontend Select already consumes.
 */
@RestController
@RequestMapping("/api/llm")
public class LlmModelsController {

    private final LlmProperties llmProperties;

    public LlmModelsController(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    public record ModelOption(String id, String label, String provider, String model, boolean isDefault) {}

    @GetMapping("/models")
    public ResponseEntity<List<ModelOption>> listModels() {
        String defaultProvider = llmProperties.getDefaultProvider();
        Map<String, LlmProperties.ProviderConfig> providers = llmProperties.getProviders();
        List<ModelOption> options = providers.entrySet().stream()
                .filter(e -> e.getValue() != null
                        && e.getValue().getModel() != null
                        && !e.getValue().getModel().isBlank())
                .map(e -> {
                    String providerName = e.getKey();
                    String model = e.getValue().getModel();
                    String id = providerName + ":" + model;
                    return new ModelOption(id, id, providerName, model, providerName.equals(defaultProvider));
                })
                .toList();
        return ResponseEntity.ok(options);
    }
}
