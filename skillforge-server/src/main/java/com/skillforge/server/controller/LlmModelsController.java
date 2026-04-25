package com.skillforge.server.controller;

import com.skillforge.core.llm.ProviderProtocolFamily;
import com.skillforge.core.llm.ProviderProtocolFamilyResolver;
import com.skillforge.server.config.LlmProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    public record ModelOption(
            String id,
            String label,
            String provider,
            String model,
            boolean isDefault,
            boolean supportsThinking,
            boolean supportsReasoningEffort,
            String protocolFamily) {}

    @GetMapping("/models")
    public ResponseEntity<List<ModelOption>> listModels() {
        String defaultProvider = llmProperties.getDefaultProvider();
        Map<String, LlmProperties.ProviderConfig> providers = llmProperties.getProviders();
        List<ModelOption> options = providers.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .flatMap(e -> {
                    String providerName = e.getKey();
                    LlmProperties.ProviderConfig provider = e.getValue();
                    List<String> modelCandidates = new ArrayList<>();
                    if (provider.getModels() != null) {
                        modelCandidates.addAll(provider.getModels());
                    }
                    if (provider.getModel() != null && !provider.getModel().isBlank()) {
                        modelCandidates.add(provider.getModel());
                    }
                    return modelCandidates.stream()
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(m -> !m.isBlank())
                            .distinct()
                            .map(model -> {
                                String id = providerName + ":" + model;
                                boolean isDefault = providerName.equals(defaultProvider)
                                        && model.equals(provider.getModel());
                                ProviderProtocolFamily family = resolveFamilyForDisplay(providerName, model);
                                return new ModelOption(
                                        id,
                                        id,
                                        providerName,
                                        model,
                                        isDefault,
                                        family.supportsThinkingToggle,
                                        family.supportsReasoningEffort,
                                        family.name().toLowerCase(Locale.ROOT));
                            });
                })
                .toList();
        return ResponseEntity.ok(options);
    }

    /**
     * Classify the model for UI capability flags. Claude models go through
     * {@code ClaudeProvider}, never {@code OpenAiProvider}; they have no thinking toggle
     * today, so the resolver's {@code CLAUDE} classification already yields the right flags.
     */
    private ProviderProtocolFamily resolveFamilyForDisplay(String providerName, String model) {
        if ("claude".equalsIgnoreCase(providerName)) {
            return ProviderProtocolFamily.CLAUDE;
        }
        return ProviderProtocolFamilyResolver.resolve(model);
    }
}
