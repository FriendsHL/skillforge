package com.skillforge.core.context;

import com.skillforge.core.llm.cache.SystemPromptParts;

import java.util.List;

/**
 * Preserves the pre-P1 stable/dynamic concatenation byte-for-byte.
 */
public final class LegacyCompatiblePromptRenderer implements PromptRenderer {

    private final boolean preserveDynamicTrailingWhitespace;

    public LegacyCompatiblePromptRenderer() {
        this(false);
    }

    /**
     * @param preserveDynamicTrailingWhitespace true after legacy runtime appenders have
     *        added Session Context or Memory fragments whose final newline was historically
     *        sent to providers.
     */
    public LegacyCompatiblePromptRenderer(boolean preserveDynamicTrailingWhitespace) {
        this.preserveDynamicTrailingWhitespace = preserveDynamicTrailingWhitespace;
    }

    @Override
    public SystemPromptParts render(List<ContextAttachment> attachments) {
        StringBuilder stable = new StringBuilder();
        StringBuilder dynamic = new StringBuilder();
        for (ContextAttachment attachment : attachments) {
            if (attachment.placement() == PromptPlacement.STABLE_SYSTEM) {
                stable.append(attachment.content());
            } else if (attachment.placement() == PromptPlacement.DYNAMIC_SYSTEM) {
                dynamic.append(attachment.content());
            }
        }
        return new SystemPromptParts(
                stable.toString().stripTrailing(),
                preserveDynamicTrailingWhitespace
                        ? dynamic.toString()
                        : dynamic.toString().stripTrailing());
    }
}
