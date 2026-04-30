package com.skillforge.core.engine;

import com.skillforge.core.model.Message;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Non-blocking interactive control emitted by the loop when human input is required.
 * The server persists this payload as a chat-row card and resumes from it later.
 */
public class InteractiveControlRequest {
    private String controlId;
    private String interactionKind;
    private String toolUseId;
    private String toolName;
    private String question;
    private String context;
    private List<Map<String, Object>> options = Collections.emptyList();
    private boolean allowOther = true;
    private Message assistantToolUseMessage;
    private Map<String, Object> extra = Collections.emptyMap();

    public String getControlId() {
        return controlId;
    }

    public void setControlId(String controlId) {
        this.controlId = controlId;
    }

    public String getInteractionKind() {
        return interactionKind;
    }

    public void setInteractionKind(String interactionKind) {
        this.interactionKind = interactionKind;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public List<Map<String, Object>> getOptions() {
        return options;
    }

    public void setOptions(List<Map<String, Object>> options) {
        this.options = options != null ? options : Collections.emptyList();
    }

    public boolean isAllowOther() {
        return allowOther;
    }

    public void setAllowOther(boolean allowOther) {
        this.allowOther = allowOther;
    }

    public Message getAssistantToolUseMessage() {
        return assistantToolUseMessage;
    }

    public void setAssistantToolUseMessage(Message assistantToolUseMessage) {
        this.assistantToolUseMessage = assistantToolUseMessage;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra != null ? extra : Collections.emptyMap();
    }
}
