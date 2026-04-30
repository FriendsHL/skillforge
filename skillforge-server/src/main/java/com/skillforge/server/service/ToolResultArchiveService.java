package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P9-2 持久化归档服务。
 *
 * <p>核心职责：
 * <ol>
 *   <li><strong>聚合预算检测</strong>：扫描单条 user message 的 tool_result 总 chars，
 *       超过 per-message 预算时按 size 降序归档大块到 {@code t_tool_result_archive}。</li>
 *   <li><strong>幂等不翻转</strong>：每次入口先 lookup (session_id, tool_use_id)；命中沿用
 *       首次决策，未命中且超预算才 insert。DB UNIQUE 约束 + service 层 idempotent upsert
 *       共同兜底（参考 Claude Code {@code partitionByPriorDecision}）。</li>
 *   <li><strong>读时替换</strong>：{@link #applyArchive(String, List)} 把归档过的
 *       tool_result block 替换为 archive_id + 2KB preview 块；未归档原样返回。</li>
 * </ol>
 *
 * <p>preview 文本格式与 request-time 裁剪不同，便于运维 / reviewer 区分：
 * 持久化归档暴露 {@code archive_id}（理论上可未来加 retrieve skill），
 * request-time 则只标 "trimmed for request"。
 */
@Service
public class ToolResultArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ToolResultArchiveService.class);

    /** 单条 user message 中 tool_result 总 chars 预算上限；超过即触发归档。 */
    public static final int DEFAULT_PER_MESSAGE_AGGREGATE_CHARS = 200_000;

    /** archive 写入 message 的 preview 块长度（head only，与 PRD 数据模型对齐）。 */
    public static final int PREVIEW_HEAD_CHARS = 2_048;

    private final ToolResultArchiveRepository archiveRepository;

    public ToolResultArchiveService(ToolResultArchiveRepository archiveRepository) {
        this.archiveRepository = archiveRepository;
    }

    @Transactional(readOnly = true)
    public Optional<ToolResultArchiveEntity> findByArchiveId(String archiveId) {
        if (archiveId == null || archiveId.isBlank()) return Optional.empty();
        return archiveRepository.findByArchiveId(archiveId);
    }

    /**
     * 对 messages 应用归档：
     * <ol>
     *   <li>对每条 user/role message：扫描 tool_result blocks。</li>
     *   <li>命中已归档 (session_id, tool_use_id) → 用 preview 替换。</li>
     *   <li>未归档但 message 内 tool_result 聚合超预算 → 按 chars 降序归档大块到表，preview 替换；
     *       UNIQUE 约束并发保护，遇 DataIntegrityViolation 重新 lookup 沿用首次决策。</li>
     * </ol>
     *
     * <p>返回新 list；不修改入参 messages 和原 ContentBlock。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Message> applyArchive(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty() || sessionId == null) {
            return messages != null ? messages : Collections.emptyList();
        }

        // Pre-load existing archive decisions for this session in a single query.
        Map<String, ToolResultArchiveEntity> existing = new HashMap<>();
        for (ToolResultArchiveEntity e : archiveRepository.findBySessionId(sessionId)) {
            existing.put(e.getToolUseId(), e);
        }

        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            out.add(processMessage(sessionId, m, existing));
        }
        return out;
    }

    /**
     * 处理单条消息：先把已归档 block 替换为 preview；再判断 message 内 tool_result 聚合
     * 是否超预算，超则按 chars 降序归档大块（idempotent upsert）并替换。
     */
    private Message processMessage(String sessionId, Message original,
                                    Map<String, ToolResultArchiveEntity> existing) {
        if (original == null || !(original.getContent() instanceof List<?> blocks) || blocks.isEmpty()) {
            return original;
        }

        // Phase 1: snapshot block-level data (toolUseId, content, chars, isError, errorType).
        List<BlockSnap> snaps = new ArrayList<>(blocks.size());
        int aggregate = 0;
        boolean hasAnyToolResult = false;
        for (Object obj : blocks) {
            BlockSnap s = snapshotBlock(obj);
            snaps.add(s);
            if (s != null && s.isToolResult) {
                hasAnyToolResult = true;
                aggregate += s.chars;
            }
        }
        if (!hasAnyToolResult) {
            return original;
        }

        // Phase 2: figure out per-block decision: archived (existing) | toArchive (this pass) | keep.
        // toArchive：从 (未归档 & 未命中 existing) 候选里按 chars 降序选直到 aggregate 回到预算内。
        boolean[] markedArchive = new boolean[snaps.size()];
        // First, deduct chars for already-archived blocks (they'll be replaced anyway).
        int retained = aggregate;
        for (int i = 0; i < snaps.size(); i++) {
            BlockSnap s = snaps.get(i);
            if (s == null || !s.isToolResult) continue;
            if (s.toolUseId != null && existing.containsKey(s.toolUseId)) {
                // Already archived: counts as preview-sized after replacement.
                retained -= s.chars;
            }
        }
        if (retained > DEFAULT_PER_MESSAGE_AGGREGATE_CHARS) {
            // Need to archive more. Sort fresh candidates by chars desc.
            List<Integer> freshIndices = new ArrayList<>();
            for (int i = 0; i < snaps.size(); i++) {
                BlockSnap s = snaps.get(i);
                if (s != null && s.isToolResult
                        && s.toolUseId != null
                        && !existing.containsKey(s.toolUseId)) {
                    freshIndices.add(i);
                }
            }
            freshIndices.sort((a, b) -> Integer.compare(snaps.get(b).chars, snaps.get(a).chars));
            for (int idx : freshIndices) {
                if (retained <= DEFAULT_PER_MESSAGE_AGGREGATE_CHARS) break;
                BlockSnap s = snaps.get(idx);
                // After archiving, the block becomes preview-sized; effectively removes its chars.
                markedArchive[idx] = true;
                retained -= s.chars;
            }
        }

        // Phase 3: persist archive rows for marked-archive blocks. Idempotent upsert.
        for (int i = 0; i < snaps.size(); i++) {
            if (!markedArchive[i]) continue;
            BlockSnap s = snaps.get(i);
            ToolResultArchiveEntity entity = upsertArchive(sessionId, s);
            if (entity != null) {
                existing.put(s.toolUseId, entity);
            } else {
                // Upsert returned null = couldn't archive; leave block as-is.
                markedArchive[i] = false;
            }
        }

        // Phase 4: build replacement message content. Reuse original blocks where untouched.
        boolean dirty = false;
        for (int i = 0; i < snaps.size(); i++) {
            BlockSnap s = snaps.get(i);
            if (s == null || !s.isToolResult) continue;
            if (markedArchive[i] || (s.toolUseId != null && existing.containsKey(s.toolUseId))) {
                dirty = true;
                break;
            }
        }
        if (!dirty) {
            return original;
        }

        List<Object> newBlocks = new ArrayList<>(blocks.size());
        for (int i = 0; i < snaps.size(); i++) {
            BlockSnap s = snaps.get(i);
            Object originalObj = blocks.get(i);
            if (s == null || !s.isToolResult || s.toolUseId == null) {
                newBlocks.add(originalObj);
                continue;
            }
            ToolResultArchiveEntity entity = existing.get(s.toolUseId);
            if (entity == null) {
                newBlocks.add(originalObj);
                continue;
            }
            String previewText = buildArchivePreview(entity);
            ContentBlock replacement = ContentBlock.toolResult(
                    s.toolUseId,
                    previewText,
                    Boolean.TRUE.equals(s.isError),
                    s.errorType);
            newBlocks.add(replacement);
        }

        Message copy = new Message();
        copy.setRole(original.getRole());
        copy.setContent(newBlocks);
        copy.setReasoningContent(original.getReasoningContent());
        return copy;
    }

    /**
     * Idempotent upsert (Judge FIX-2): PostgreSQL-safe via ON CONFLICT DO NOTHING.
     * <ol>
     *   <li>First lookup — 命中沿用首次决策（mustReapply 不翻转）。</li>
     *   <li>未命中 → {@code insertIgnoreConflict}：UNIQUE 冲突静默失败，TX 不 abort
     *       （区别于 JPA save 路径的 DataIntegrityViolationException 抛出 + TX abort）。</li>
     *   <li>Insert 后再 lookup 取回 winner 行 —— 无论本次 insert 写入 0 行（并发对手赢）
     *       还是 1 行（自己赢），都能拿到 sticky 决策的同一行。</li>
     * </ol>
     *
     * <p>失败（lookup 都为空）记 warn 返回 null，让上层保留原文（明确失败优于静默丢内容）。
     */
    private ToolResultArchiveEntity upsertArchive(String sessionId, BlockSnap s) {
        // 1. First lookup
        Optional<ToolResultArchiveEntity> existing =
                archiveRepository.findBySessionIdAndToolUseId(sessionId, s.toolUseId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // 2. PostgreSQL-safe insert; ON CONFLICT DO NOTHING 不 abort 当前事务。
        String archiveId = UUID.randomUUID().toString();
        String preview = buildPreviewHead(s.content);
        String content = s.content != null ? s.content : "";
        Instant now = Instant.now();
        try {
            int rows = archiveRepository.insertIgnoreConflict(
                    archiveId,
                    sessionId,
                    null,           // sessionMessageId: 反查失败时为空（与 tech-design 一致）
                    s.toolUseId,
                    null,           // toolName: 反查失败时为空（与 tech-design 一致）
                    s.chars,
                    preview,
                    content,
                    now);
            if (rows == 0) {
                log.info("Concurrent archive insert no-op for session={}, tool_use_id={}; re-using winner",
                        sessionId, s.toolUseId);
            }
        } catch (Exception e) {
            log.warn("Tool result archive insert failed for session={}, tool_use_id={}: {}",
                    sessionId, s.toolUseId, e.toString());
            return null;
        }
        // 3. Re-lookup to fetch the winner row (regardless of who won).
        Optional<ToolResultArchiveEntity> winner =
                archiveRepository.findBySessionIdAndToolUseId(sessionId, s.toolUseId);
        if (winner.isEmpty()) {
            log.warn("Archive winner re-lookup empty for session={}, tool_use_id={}",
                    sessionId, s.toolUseId);
            return null;
        }
        return winner.get();
    }

    private static String buildPreviewHead(String content) {
        if (content == null) return "";
        if (content.length() <= PREVIEW_HEAD_CHARS) return content;
        return content.substring(0, PREVIEW_HEAD_CHARS);
    }

    private static String buildArchivePreview(ToolResultArchiveEntity e) {
        return "[Tool result archived]\n"
                + "archive_id: " + e.getArchiveId() + "\n"
                + "tool_use_id: " + e.getToolUseId() + "\n"
                + "original_chars: " + e.getOriginalChars() + "\n"
                + "preview:\n"
                + (e.getPreview() != null ? e.getPreview() : "");
    }

    /** Snapshot of a tool_result content block (or null for unrelated blocks). */
    private static BlockSnap snapshotBlock(Object obj) {
        if (obj instanceof ContentBlock cb) {
            if (!"tool_result".equals(cb.getType())) {
                BlockSnap s = new BlockSnap();
                s.isToolResult = false;
                return s;
            }
            BlockSnap s = new BlockSnap();
            s.isToolResult = true;
            s.toolUseId = cb.getToolUseId();
            s.content = cb.getContent();
            s.chars = s.content != null ? s.content.length() : 0;
            s.isError = Boolean.TRUE.equals(cb.getIsError());
            s.errorType = cb.getErrorType();
            return s;
        }
        if (obj instanceof Map<?, ?> map) {
            if (!"tool_result".equals(map.get("type"))) {
                BlockSnap s = new BlockSnap();
                s.isToolResult = false;
                return s;
            }
            BlockSnap s = new BlockSnap();
            s.isToolResult = true;
            s.toolUseId = map.get("tool_use_id") != null ? map.get("tool_use_id").toString() : null;
            Object contentVal = map.get("content");
            s.content = contentVal instanceof String str ? str
                    : (contentVal != null ? contentVal.toString() : null);
            s.chars = s.content != null ? s.content.length() : 0;
            s.isError = Boolean.TRUE.equals(map.get("is_error"));
            Object et = map.get("error_type");
            s.errorType = et != null ? et.toString() : null;
            return s;
        }
        return null;
    }

    private static final class BlockSnap {
        boolean isToolResult;
        String toolUseId;
        String content;
        int chars;
        boolean isError;
        String errorType;
    }
}
