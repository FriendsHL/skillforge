package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 消息内容块，支持 text / tool_use / tool_result / attachment reference types.
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
    /**
     * 错误子类型，仅 is_error=true 时有意义。
     * 取值与 {@code com.skillforge.core.skill.SkillResult.ErrorType} 一致字符串
     * （"VALIDATION" / "EXECUTION"），保留 String 而非枚举以保证向后兼容反序列化。
     * 不向 LLM provider 上行（Anthropic/OpenAI 协议无此字段），仅在引擎内部消费
     * （如 detectWaste 区分 LLM 入参错误 vs 真实执行失败）。
     */
    @JsonProperty("error_type")
    private String errorType;

    // attachment reference types: image_ref / pdf_ref / word_ref / excel_ref / csv_ref
    @JsonProperty("attachment_id")
    private String attachmentId;
    @JsonProperty("mime_type")
    private String mimeType;
    private String filename;
    @JsonProperty("page_count")
    private Integer pageCount;
    /**
     * Wave 3 WORD-EXCEL: number of sheets in an Excel workbook for {@code excel_ref}
     * blocks. Nullable Integer — left null at upload time and refined on first
     * materialization once the parser actually opens the workbook. Other ref
     * types (image_ref / pdf_ref / word_ref / csv_ref) leave this null. Wire field
     * name {@code sheet_count} matches the snake_case convention used by image_ref /
     * pdf_ref (FE collapses to camelCase via the same Jackson path).
     */
    @JsonProperty("sheet_count")
    private Integer sheetCount;

    // provider-bound materialized image type; never persisted in session history.
    @JsonProperty("data_base64")
    private String dataBase64;

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
        return toolResult(toolUseId, content, isError, null);
    }

    /**
     * 创建带错误子类型的 tool_result 内容块。
     */
    public static ContentBlock toolResult(String toolUseId, String content, boolean isError, String errorType) {
        ContentBlock block = new ContentBlock();
        block.setType("tool_result");
        block.setToolUseId(toolUseId);
        block.setContent(content);
        block.setIsError(isError);
        block.setErrorType(errorType);
        return block;
    }

    public static ContentBlock imageRef(String attachmentId, String mimeType, String filename) {
        ContentBlock block = new ContentBlock();
        block.setType("image_ref");
        block.setAttachmentId(attachmentId);
        block.setMimeType(mimeType);
        block.setFilename(filename);
        return block;
    }

    public static ContentBlock pdfRef(String attachmentId, String filename, Integer pageCount) {
        ContentBlock block = new ContentBlock();
        block.setType("pdf_ref");
        block.setAttachmentId(attachmentId);
        block.setFilename(filename);
        block.setPageCount(pageCount);
        return block;
    }

    public static ContentBlock image(String mimeType, String dataBase64) {
        ContentBlock block = new ContentBlock();
        block.setType("image");
        block.setMimeType(mimeType);
        block.setDataBase64(dataBase64);
        return block;
    }

    /**
     * Wave 3 WORD-EXCEL: reference block for a Word document (.doc / .docx)
     * attachment. Persisted in {@code t_session_message.content_json}; the
     * materializer expands this to a single text block carrying the markdown
     * extraction at provider-call time.
     */
    public static ContentBlock wordRef(String attachmentId, String filename) {
        ContentBlock block = new ContentBlock();
        block.setType("word_ref");
        block.setAttachmentId(attachmentId);
        block.setFilename(filename);
        return block;
    }

    /**
     * Wave 3 WORD-EXCEL: reference block for an Excel workbook (.xlsx / .xls)
     * attachment. {@code sheetCount} is left null at upload time and refined on
     * first materialization (parser exposes the count).
     */
    public static ContentBlock excelRef(String attachmentId, String filename, Integer sheetCount) {
        ContentBlock block = new ContentBlock();
        block.setType("excel_ref");
        block.setAttachmentId(attachmentId);
        block.setFilename(filename);
        block.setSheetCount(sheetCount);
        return block;
    }

    /**
     * Wave 3 WORD-EXCEL: reference block for a CSV file attachment. No structural
     * metadata up front — CSV is parsed inline by the materializer.
     */
    public static ContentBlock csvRef(String attachmentId, String filename) {
        ContentBlock block = new ContentBlock();
        block.setType("csv_ref");
        block.setAttachmentId(attachmentId);
        block.setFilename(filename);
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

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getAttachmentId() {
        return attachmentId;
    }

    public void setAttachmentId(String attachmentId) {
        this.attachmentId = attachmentId;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public String getDataBase64() {
        return dataBase64;
    }

    public void setDataBase64(String dataBase64) {
        this.dataBase64 = dataBase64;
    }

    public Integer getSheetCount() {
        return sheetCount;
    }

    public void setSheetCount(Integer sheetCount) {
        this.sheetCount = sheetCount;
    }
}
