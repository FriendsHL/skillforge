package com.skillforge.core.engine.durability;

import com.skillforge.core.model.ContentBlock;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of a JSON-compatible value.
 *
 * <p>No Jackson instance is owned here. Persistence implementations materialize a fresh Java
 * value and pass it through the shared server codec and its Spring-managed ObjectMapper.
 */
public final class FrozenJson {

    private final Object value;

    private FrozenJson(Object value) {
        this.value = freeze(value);
    }

    public static FrozenJson capture(Object value) {
        return new FrozenJson(value);
    }

    /** Returns a fresh mutable tree suitable for Message or codec construction. */
    public Object toJavaValue() {
        return thaw(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> immutableObject(Map<String, Object> value) {
        Object frozen = freeze(value == null ? Map.of() : value);
        return (Map<String, Object>) frozen;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof FrozenJson that && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    private static Object freeze(Object source) {
        if (source == null || source instanceof String || source instanceof Boolean
                || source instanceof BigDecimal || source instanceof BigInteger
                || source instanceof Byte || source instanceof Short || source instanceof Integer
                || source instanceof Long || source instanceof Float || source instanceof Double) {
            return source;
        }
        if (source instanceof ContentBlock block) {
            return freeze(contentBlockMap(block));
        }
        if (source instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> frozen = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                frozen.put(key, freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(frozen);
        }
        if (source instanceof Iterable<?> iterable) {
            List<Object> frozen = new ArrayList<>();
            for (Object element : iterable) {
                frozen.add(freeze(element));
            }
            return Collections.unmodifiableList(frozen);
        }
        if (source.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(source);
            List<Object> frozen = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                frozen.add(freeze(java.lang.reflect.Array.get(source, i)));
            }
            return Collections.unmodifiableList(frozen);
        }
        throw new IllegalArgumentException(
                "Unsupported JSON value type: " + source.getClass().getName());
    }

    private static Object thaw(Object source) {
        if (source instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>(map.size());
            map.forEach((key, value) -> copy.put((String) key, thaw(value)));
            return copy;
        }
        if (source instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) copy.add(thaw(element));
            return copy;
        }
        return source;
    }

    private static Map<String, Object> contentBlockMap(ContentBlock block) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        putIfNonNull(value, "type", block.getType());
        putIfNonNull(value, "text", block.getText());
        putIfNonNull(value, "id", block.getId());
        putIfNonNull(value, "name", block.getName());
        putIfNonNull(value, "input", block.getInput());
        putIfNonNull(value, "tool_use_id", block.getToolUseId());
        putIfNonNull(value, "content", block.getContent());
        putIfNonNull(value, "is_error", block.getIsError());
        putIfNonNull(value, "error_type", block.getErrorType());
        putIfNonNull(value, "attachment_id", block.getAttachmentId());
        putIfNonNull(value, "mime_type", block.getMimeType());
        putIfNonNull(value, "filename", block.getFilename());
        putIfNonNull(value, "caption", block.getCaption());
        putIfNonNull(value, "page_count", block.getPageCount());
        putIfNonNull(value, "sheet_count", block.getSheetCount());
        putIfNonNull(value, "title", block.getTitle());
        putIfNonNull(value, "artifact_schema_version", block.getArtifactSchemaVersion());
        putIfNonNull(value, "job_id", block.getJobId());
        putIfNonNull(value, "media_type", block.getMediaType());
        putIfNonNull(value, "data_base64", block.getDataBase64());
        return value;
    }

    private static void putIfNonNull(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
