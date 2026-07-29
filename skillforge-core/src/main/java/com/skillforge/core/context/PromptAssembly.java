package com.skillforge.core.context;

import com.skillforge.core.llm.cache.SystemPromptParts;

import java.util.List;
import java.util.ArrayList;

public final class PromptAssembly {

    private final List<ContextAttachment> attachments;

    public PromptAssembly(List<ContextAttachment> attachments) {
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public List<ContextAttachment> attachments() {
        return attachments;
    }

    public PromptAssembly withAttachment(ContextAttachment attachment) {
        if (attachment == null || attachment.content().isEmpty()) {
            return this;
        }
        List<ContextAttachment> combined = new ArrayList<>(attachments.size() + 1);
        combined.addAll(attachments);
        combined.add(attachment);
        return new PromptAssembly(combined);
    }

    public SystemPromptParts render(PromptRenderer renderer) {
        return renderer.render(attachments);
    }

    public List<PromptFragmentObservation> observations() {
        return attachments.stream()
                .map(attachment -> new PromptFragmentObservation(
                        attachment.id(), attachment.sourceType(), attachment.placement(),
                        attachment.stable(), attachment.cacheable(), attachment.estimatedTokens(),
                        attachment.sourceIds(), attachment.contentHash()))
                .toList();
    }
}
