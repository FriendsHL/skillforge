package com.skillforge.observability.api;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Blob 存储接口（文件系统 / S3 / 等的抽象）。
 *
 * <p>plan §5.1 R3-WN3：{@link #openStream} 是流式读取入口，避免一次性 byte[] 装载导致 server OOM。
 */
public interface BlobStore {

    /** 写入；返回相对路径 ref（用于落 DB）。 */
    String write(BlobRef ref, byte[] payload) throws IOException;

    /** 一次性读取（小 payload 场景）。 */
    Optional<byte[]> read(String blobRef) throws IOException;

    /** 流式读取；返回 empty 表示文件不存在。调用方负责 close。 */
    Optional<InputStream> openStream(String blobRef) throws IOException;

    /** 列出比 cutoff 更早的 blob refs（retention 用）。 */
    List<String> listOlderThan(Instant cutoff, int limit);

    /** 删除单个 blob；失败仅 log，不抛。 */
    void delete(String blobRef);
}
