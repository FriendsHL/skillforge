package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 消息内容块，支持 text / tool_use / tool_result 三种类型。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentBlock {

    private String type;

    // text type
    private String text;

    // tool_use type
    private String id;
    private String name;
    private Map<String, Object> input;

    // tool_result type
    @JsonProperty("tool_use_id")
    private String toolUseId;
    private String content;
    @JsonProperty("is_error")
    private Boolean isError;

    public ContentBlock() {
    }

    /**
     * 创建文本内容块。
     */
    public static ContentBlock text(String text) {
        ContentBlock block = new ContentBlock();
        block.setType("text");
        block.setText(text);
        return block;
    }

    /**
     * 创建 tool_use 内容块。
     */
    public static ContentBlock toolUse(String id, String name, Map<String, Object> input) {
        ContentBlock block = new ContentBlock();
        block.setType("tool_use");
        block.setId(id);
        block.setName(name);
        block.setInput(input);
        return block;
    }

    /**
     * 创建 tool_result 内容块。
     */
    public static ContentBlock toolResult(String toolUseId, String content, boolean isError) {
        ContentBlock block = new ContentBlock();
        block.setType("tool_result");
        block.setToolUseId(toolUseId);
        block.setContent(content);
        block.setIsError(isError);
        return block;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Boolean getIsError() {
        return isError;
    }

    public void setIsError(Boolean isError) {
        this.isError = isError;
    }
}
