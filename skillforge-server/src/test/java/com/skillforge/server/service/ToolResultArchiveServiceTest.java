package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.entity.ToolResultArchiveEntity;
import com.skillforge.server.repository.ToolResultArchiveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolResultArchiveServiceTest {

    @Mock private ToolResultArchiveRepository archiveRepository;

    private ToolResultArchiveService service;

    @BeforeEach
    void setUp() {
        service = new ToolResultArchiveService(archiveRepository);
    }

    private static Message toolResultMsg(String toolUseId, int chars) {
        return Message.toolResult(toolUseId, "x".repeat(chars), false);
    }

    private static String contentOf(Message m) {
        ContentBlock cb = (ContentBlock) ((List<?>) m.getContent()).get(0);
        return cb.getContent();
    }

    @Test
    @DisplayName("聚合在预算内时不归档，原 messages 直接返回")
    void applyArchive_underBudget_returnsOriginal() {
        when(archiveRepository.findBySessionId("s1")).thenReturn(Collections.emptyList());

        List<Message> in = new ArrayList<>();
        in.add(Message.user("hi"));
        in.add(toolResultMsg("t1", 5_000));
        in.add(toolResultMsg("t2", 5_000));

        List<Message> out = service.applyArchive("s1", in);

        // Same references — no rewrite.
        assertThat(out.get(1)).isSameAs(in.get(1));
        assertThat(out.get(2)).isSameAs(in.get(2));
        verify(archiveRepository, never()).insertIgnoreConflict(
                anyString(), anyString(), any(), anyString(), any(),
                anyInt(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("聚合超 200K 时按 size 降序归档，PG-safe insert 写表，preview 替换")
    void applyArchive_overBudget_archivesLargestFirst() {
        when(archiveRepository.findBySessionId("s1")).thenReturn(Collections.emptyList());
        // Simulate PG ON CONFLICT DO NOTHING + re-lookup pattern: per-toolUseId state
        // tracks pre-insert (empty) → post-insert (row exists). Single thenAnswer keyed
        // off invocationCounter so big1 / big2 each see independent pre/post-insert state.
        when(archiveRepository.findBySessionIdAndToolUseId(eq("s1"), anyString()))
                .thenAnswer(inv -> {
                    String tid = inv.getArgument(1);
                    return invocationCounter.getOrDefault(tid, 0) == 0
                            ? Optional.empty()
                            : Optional.of(makeEntity(tid));
                });
        when(archiveRepository.insertIgnoreConflict(
                anyString(), eq("s1"), any(), anyString(), any(),
                anyInt(), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    String tid = inv.getArgument(3);
                    invocationCounter.merge(tid, 1, Integer::sum);
                    return 1;
                });

        // Build a single user message with multiple tool_result blocks. Aggregate
        // 10K + 250K + 250K = 510K. After archiving the first 250K block retained = 260K
        // (still > 200K budget), so the second 250K must also be archived.
        List<Object> blocks = new ArrayList<>();
        blocks.add(ContentBlock.toolResult("small", "y".repeat(10_000), false));
        blocks.add(ContentBlock.toolResult("big1", "y".repeat(250_000), false));
        blocks.add(ContentBlock.toolResult("big2", "y".repeat(250_000), false));
        Message bundled = new Message();
        bundled.setRole(Message.Role.USER);
        bundled.setContent(blocks);

        List<Message> in = new ArrayList<>();
        in.add(bundled);

        List<Message> out = service.applyArchive("s1", in);

        // Both 250K blocks must be archived via insertIgnoreConflict (small stays).
        verify(archiveRepository, times(2)).insertIgnoreConflict(
                anyString(), eq("s1"), any(), anyString(), any(),
                anyInt(), anyString(), anyString(), any());

        @SuppressWarnings("unchecked")
        List<Object> outBlocks = (List<Object>) out.get(0).getContent();
        ContentBlock smallOut = (ContentBlock) outBlocks.get(0);
        ContentBlock bigOut1 = (ContentBlock) outBlocks.get(1);
        ContentBlock bigOut2 = (ContentBlock) outBlocks.get(2);
        assertThat(smallOut.getContent()).hasSize(10_000);
        assertThat(bigOut1.getContent()).contains("[Tool result archived]")
                .contains("tool_use_id: big1");
        assertThat(bigOut2.getContent()).contains("tool_use_id: big2");
        // Original messages list untouched.
        ContentBlock origBig = (ContentBlock) blocks.get(1);
        assertThat(origBig.getContent()).hasSize(250_000);
    }

    /** Tracks per-toolUseId post-insert lookup state for the overBudget Mockito stub. */
    private final java.util.Map<String, Integer> invocationCounter = new java.util.HashMap<>();

    /** Helper to build a synthetic post-insert lookup result row matching the toolUseId. */
    private static ToolResultArchiveEntity makeEntity(String toolUseId) {
        ToolResultArchiveEntity e = new ToolResultArchiveEntity();
        e.setId(System.nanoTime());
        e.setArchiveId("aid-" + toolUseId);
        e.setSessionId("s1");
        e.setToolUseId(toolUseId);
        e.setOriginalChars(250_000);
        e.setPreview("preview-" + toolUseId);
        return e;
    }

    @Test
    @DisplayName("二次入口沿用首次决策：findBySessionId 返回已归档时直接 preview 替换，不再 save")
    void applyArchive_secondPass_reusesExistingDecision() {
        ToolResultArchiveEntity existing = new ToolResultArchiveEntity();
        existing.setArchiveId("aid-big1");
        existing.setSessionId("s1");
        existing.setToolUseId("big1");
        existing.setOriginalChars(250_000);
        existing.setPreview("preview-content");

        when(archiveRepository.findBySessionId("s1")).thenReturn(List.of(existing));

        List<Object> blocks = new ArrayList<>();
        blocks.add(ContentBlock.toolResult("big1", "y".repeat(250_000), false));
        Message bundled = new Message();
        bundled.setRole(Message.Role.USER);
        bundled.setContent(blocks);

        List<Message> out = service.applyArchive("s1", List.of(bundled));

        @SuppressWarnings("unchecked")
        List<Object> outBlocks = (List<Object>) out.get(0).getContent();
        ContentBlock bigOut = (ContentBlock) outBlocks.get(0);
        assertThat(bigOut.getContent()).contains("archive_id: aid-big1");
        verify(archiveRepository, never()).insertIgnoreConflict(
                anyString(), anyString(), any(), anyString(), any(),
                anyInt(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("PG-safe UPSERT：ON CONFLICT DO NOTHING 写 0 行（输家），后续 lookup 取回胜出方")
    void applyArchive_uniqueViolation_reusesWinner() {
        // Judge FIX-2: 验证的是 "PG-safe ON CONFLICT DO NOTHING + 后续 lookup 取胜出方"
        // 语义。原 Mockito 走 catch(DataIntegrityViolation)+re-lookup 的代码路径在
        // PostgreSQL TX aborted 状态下不可行；当前实现：insertIgnoreConflict 返回写入行数
        // (0 = 输家、1 = 自己赢)，再 findBySessionIdAndToolUseId 拿胜出方。
        when(archiveRepository.findBySessionId("s1")).thenReturn(Collections.emptyList());
        when(archiveRepository.findBySessionIdAndToolUseId("s1", "big1"))
                .thenReturn(Optional.empty())                       // first lookup before insert
                .thenReturn(Optional.of(makeWinner("big1")));       // post-insert lookup → winner
        when(archiveRepository.insertIgnoreConflict(
                anyString(), eq("s1"), any(), eq("big1"), any(),
                anyInt(), anyString(), anyString(), any()))
                .thenReturn(0); // concurrent winner already inserted → 0 rows affected

        List<Object> blocks = new ArrayList<>();
        blocks.add(ContentBlock.toolResult("big1", "y".repeat(250_000), false));
        Message bundled = new Message();
        bundled.setRole(Message.Role.USER);
        bundled.setContent(blocks);

        List<Message> out = service.applyArchive("s1", List.of(bundled));

        @SuppressWarnings("unchecked")
        List<Object> outBlocks = (List<Object>) out.get(0).getContent();
        ContentBlock bigOut = (ContentBlock) outBlocks.get(0);
        assertThat(bigOut.getContent()).contains("archive_id: aid-big1-winner");
    }

    @Test
    @DisplayName("compaction interaction: 重组消息后已归档 tool_use_id 仍 preview，未归档保留原文")
    void applyArchive_afterCompaction_archiveLookupByToolUseId_stillReturnsPreview() {
        // Judge FIX-3: tech-design 测试计划明文 "compaction interaction 测试" 落地。
        // 模拟 compact 后的消息列表 —— 已归档 tool_use_id "archived-1" 仍存在但消息位置/上下文
        // 变化（如被并入新 user 消息后续 turn）；未归档的 "fresh-1" 保留原文。
        // 不变量：archive 决策对同一 (session_id, tool_use_id) 不翻转，与消息位置无关。
        ToolResultArchiveEntity archived = new ToolResultArchiveEntity();
        archived.setArchiveId("aid-archived-1");
        archived.setSessionId("s1");
        archived.setToolUseId("archived-1");
        archived.setOriginalChars(300_000);
        archived.setPreview("preview-of-archived-1");
        when(archiveRepository.findBySessionId("s1")).thenReturn(List.of(archived));

        // "Compact 重组后" 的消息：
        // - msg[0] 是 compact 摘要（普通 user text，无 tool_result）
        // - msg[1] 携带已归档 archived-1 + 未归档 fresh-1（聚合在预算内，不会触发新归档）
        Message summary = Message.user("[Compact summary] previous turns merged");
        List<Object> blocks = new ArrayList<>();
        blocks.add(ContentBlock.toolResult("archived-1", "y".repeat(300_000), false));
        blocks.add(ContentBlock.toolResult("fresh-1", "z".repeat(5_000), false));
        Message reassembled = new Message();
        reassembled.setRole(Message.Role.USER);
        reassembled.setContent(blocks);

        List<Message> out = service.applyArchive("s1", List.of(summary, reassembled));

        // summary 透传不变（无 tool_result）
        assertThat(out.get(0)).isSameAs(summary);
        // 已归档块：preview 替换，archive_id 命中已存在记录（不写新 archive 行）
        @SuppressWarnings("unchecked")
        List<Object> outBlocks = (List<Object>) out.get(1).getContent();
        ContentBlock archivedOut = (ContentBlock) outBlocks.get(0);
        ContentBlock freshOut = (ContentBlock) outBlocks.get(1);
        assertThat(archivedOut.getContent()).contains("[Tool result archived]")
                .contains("archive_id: aid-archived-1")
                .contains("tool_use_id: archived-1");
        // 未归档块：原文保留（同一引用穿透）
        assertThat(freshOut).isSameAs(blocks.get(1));
        assertThat(freshOut.getContent()).hasSize(5_000);
        // 不变量：本次 applyArchive 不写新 archive 行（聚合在预算内 + archived-1 命中既有记录）
        verify(archiveRepository, never()).insertIgnoreConflict(
                anyString(), anyString(), any(), anyString(), any(),
                anyInt(), anyString(), anyString(), any());
        // 原始 messages 引用不被 mutate
        assertThat(((ContentBlock) blocks.get(0)).getContent()).hasSize(300_000);
    }

    @Test
    @DisplayName("非 tool_result 内容（普通 user/assistant text）原样穿透")
    void applyArchive_plainText_passThrough() {
        when(archiveRepository.findBySessionId("s1")).thenReturn(Collections.emptyList());

        Message u = Message.user("hello");
        List<Message> out = service.applyArchive("s1", List.of(u));

        assertThat(out.get(0)).isSameAs(u);
        verify(archiveRepository, never()).insertIgnoreConflict(
                anyString(), anyString(), any(), anyString(), any(),
                anyInt(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Map-shape tool_result 块也能被识别归档（兼容反序列化路径）")
    void applyArchive_mapShape_recognized() {
        when(archiveRepository.findBySessionId("s1")).thenReturn(Collections.emptyList());
        // Pre-insert lookup empty; post-insert lookup returns row.
        when(archiveRepository.findBySessionIdAndToolUseId("s1", "tx"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(makeEntity("tx")));
        when(archiveRepository.insertIgnoreConflict(
                anyString(), eq("s1"), any(), eq("tx"), any(),
                anyInt(), anyString(), anyString(), any()))
                .thenReturn(1);

        Map<String, Object> mapBlock = new java.util.LinkedHashMap<>();
        mapBlock.put("type", "tool_result");
        mapBlock.put("tool_use_id", "tx");
        mapBlock.put("content", "y".repeat(250_000));
        mapBlock.put("is_error", false);

        Message bundled = new Message();
        bundled.setRole(Message.Role.USER);
        bundled.setContent(new ArrayList<>(List.of(mapBlock)));

        List<Message> out = service.applyArchive("s1", List.of(bundled));

        verify(archiveRepository, times(1)).insertIgnoreConflict(
                anyString(), eq("s1"), any(), eq("tx"), any(),
                anyInt(), anyString(), anyString(), any());
        @SuppressWarnings("unchecked")
        List<Object> outBlocks = (List<Object>) out.get(0).getContent();
        ContentBlock cb = (ContentBlock) outBlocks.get(0);
        assertThat(cb.getContent()).contains("[Tool result archived]");
    }

    private static ToolResultArchiveEntity makeWinner(String toolUseId) {
        ToolResultArchiveEntity w = new ToolResultArchiveEntity();
        w.setArchiveId("aid-" + toolUseId + "-winner");
        w.setToolUseId(toolUseId);
        w.setSessionId("s1");
        w.setOriginalChars(250_000);
        w.setPreview("preview");
        return w;
    }
}
