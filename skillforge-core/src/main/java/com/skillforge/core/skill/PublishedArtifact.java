package com.skillforge.core.skill;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/** Typed metadata for an attachment already imported into managed chat storage. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublishedArtifact {

    private String attachmentId;
    private String blockType;
    private String filename;
    private String mimeType;
    private Integer pageCount;
    private Integer sheetCount;
    private String caption;
    private String title;
    private Integer artifactSchemaVersion;

    public PublishedArtifact() {
    }

    public PublishedArtifact(String attachmentId, String blockType, String filename,
                             String mimeType, Integer pageCount, Integer sheetCount,
                             String caption) {
        this.attachmentId = attachmentId;
        this.blockType = blockType;
        this.filename = filename;
        this.mimeType = mimeType;
        this.pageCount = pageCount;
        this.sheetCount = sheetCount;
        this.caption = caption;
    }

    public PublishedArtifact(String attachmentId, String blockType, String filename,
                             String mimeType, Integer pageCount, Integer sheetCount,
                             String caption, String title, Integer artifactSchemaVersion) {
        this(attachmentId, blockType, filename, mimeType, pageCount, sheetCount, caption);
        this.title = title;
        this.artifactSchemaVersion = artifactSchemaVersion;
    }

    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }
    public String getBlockType() { return blockType; }
    public void setBlockType(String blockType) { this.blockType = blockType; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public Integer getSheetCount() { return sheetCount; }
    public void setSheetCount(Integer sheetCount) { this.sheetCount = sheetCount; }
    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getArtifactSchemaVersion() { return artifactSchemaVersion; }
    public void setArtifactSchemaVersion(Integer artifactSchemaVersion) {
        this.artifactSchemaVersion = artifactSchemaVersion;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PublishedArtifact that)) return false;
        return Objects.equals(attachmentId, that.attachmentId)
                && Objects.equals(blockType, that.blockType)
                && Objects.equals(filename, that.filename)
                && Objects.equals(mimeType, that.mimeType)
                && Objects.equals(pageCount, that.pageCount)
                && Objects.equals(sheetCount, that.sheetCount)
                && Objects.equals(caption, that.caption)
                && Objects.equals(title, that.title)
                && Objects.equals(artifactSchemaVersion, that.artifactSchemaVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attachmentId, blockType, filename, mimeType, pageCount, sheetCount,
                caption, title, artifactSchemaVersion);
    }
}
