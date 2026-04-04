package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对话消息模型，映射 Anthropic Messages API 格式。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {

    /**
     * 消息角色枚举。
     */
    public enum Role {
        @JsonProperty("user")
        USER,
        @JsonProperty("assistant")
        ASSISTANT,
        @JsonProperty("system")
        SYSTEM
    }

    private Role role;

    /**
     * content 可以是纯文本字符串，也可以是 ContentBlock 列表。
     * 序列化时根据实际类型输出。
     */
    private Object content;

    public Message() {
    }

    /**
     * 创建用户消息。
     */
    public static Message user(String text) {
        Message msg = new Message();
        msg.setRole(Role.USER);
        msg.setContent(text);
        return msg;
    }

    /**
     * 创建助手消息。
     */
    public static Message assistant(String text) {
        Message msg = new Message();
        msg.setRole(Role.ASSISTANT);
        msg.setContent(text);
        return msg;
    }

    /**
     * 创建 tool_result 消息（role=user，content 为 tool_result 块列表）。
     */
    public static Message toolResult(String toolUseId, String content, boolean isError) {
        Message msg = new Message();
        msg.setRole(Role.USER);
        ContentBlock block = ContentBlock.toolResult(toolUseId, content, isError);
        msg.setContent(Collections.singletonList(block));
        return msg;
    }

    /**
     * 从消息中提取纯文本内容。
     * 如果 content 是字符串则直接返回；如果是 ContentBlock 列表则拼接所有 text 块。
     */
    @JsonIgnore
    public String getTextContent() {
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object obj : blocks) {
                String text = null;
                if (obj instanceof ContentBlock block && "text".equals(block.getType())) {
                    text = block.getText();
                } else if (obj instanceof Map<?, ?> map && "text".equals(map.get("type"))) {
                    Object t = map.get("text");
                    text = t != null ? t.toString() : null;
                }
                if (text != null) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 从 assistant 消息中提取所有 tool_use 块。
     */
    @SuppressWarnings("unchecked")
    @JsonIgnore
    public List<ToolUseBlock> getToolUseBlocks() {
        if (!(content instanceof List<?> blocks)) {
            return Collections.emptyList();
        }
        List<ToolUseBlock> result = new ArrayList<>();
        for (Object obj : blocks) {
            if (obj instanceof ContentBlock block && "tool_use".equals(block.getType())) {
                result.add(new ToolUseBlock(block.getId(), block.getName(), block.getInput()));
            } else if (obj instanceof Map<?, ?> map && "tool_use".equals(map.get("type"))) {
                String id = map.get("id") != null ? map.get("id").toString() : "";
                String name = map.get("name") != null ? map.get("name").toString() : "";
                Map<String, Object> input = map.get("input") instanceof Map
                        ? (Map<String, Object>) map.get("input") : Collections.emptyMap();
                result.add(new ToolUseBlock(id, name, input));
            }
        }
        return result;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }
}
