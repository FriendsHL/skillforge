package com.skillforge.core.context;

import com.skillforge.core.llm.cache.SystemPromptParts;

import java.util.List;

public interface PromptRenderer {
    SystemPromptParts render(List<ContextAttachment> attachments);
}
