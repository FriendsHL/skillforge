package com.skillforge.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.engine.ToolCallRecord;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import com.skillforge.server.dto.SessionReplayDto;
import com.skillforge.server.dto.SessionReplayDto.Iteration;
import com.skillforge.server.dto.SessionReplayDto.ReplayToolCall;
import com.skillforge.server.dto.SessionReplayDto.Turn;
import com.skillforge.server.entity.ModelUsageEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.ModelUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建 Session Replay 结构化视图：将扁平消息列表 + model usage 记录
 * 合并为 turn → iteration → tool call 三层时间线。
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final SessionService sessionService;
    private final ModelUsageRepository modelUsageRepository;
    private final ObjectMapper objectMapper;

    public ReplayService(SessionService sessionService,
                         ModelUsageRepository modelUsageRepository,
                         ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.modelUsageRepository = modelUsageRepository;
        this.objectMapper = objectMapper;
    }

    public SessionReplayDto buildReplay(String sessionId) {
        SessionEntity session = sessionService.getSession(sessionId);
        List<Message> messages = sessionService.getSessionMessages(sessionId);

        // 按 createdAt 正序获取该 session 的所有 model usage 记录（每次 loop 一条）
        List<ModelUsageEntity> usages = modelUsageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 解析每条 usage 的 toolCalls JSON → List<ToolCallRecord>
        List<List<ToolCallRecord>> usageToolCalls = new ArrayList<>();
        for (ModelUsageEntity u : usages) {
            usageToolCalls.add(parseToolCalls(u.getToolCalls()));
        }

        // 将扁平消息列表切分为 turns
        List<Turn> turns = parseTurns(messages, usages, usageToolCalls);

        SessionReplayDto dto = new SessionReplayDto();
        dto.setSessionId(sessionId);
        dto.setStatus(session.getStatus());
        dto.setRuntimeStatus(session.getRuntimeStatus());
        dto.setCreatedAt(session.getCreatedAt());
        dto.setUpdatedAt(session.getUpdatedAt());
        dto.setTurns(turns);
        return dto;
    }

    /**
     * 将消息列表按 "用户发文本" 为边界切分成多个 turn，
     * 每个 turn 内部按 assistant(tool_use) → user(tool_result) 切分为 iterations。
     */
    List<Turn> parseTurns(List<Message> messages,
                          List<ModelUsageEntity> usages,
                          List<List<ToolCallRecord>> usageToolCalls) {
        List<Turn> turns = new ArrayList<>();
        int usageIdx = 0;
        int turnIdx = 0;
        int i = 0;

        while (i < messages.size()) {
            Message msg = messages.get(i);

            // 跳过 system 消息
            if (msg.getRole() == Message.Role.SYSTEM) {
                i++;
                continue;
            }

            // 寻找 turn 的开始：一条有文本内容的 user 消息
            if (msg.getRole() == Message.Role.USER) {
                String userText = extractUserText(msg);
                if (userText.isEmpty()) {
                    // 纯 tool_result 的 user 消息，不是 turn 开始，跳过
                    i++;
                    continue;
                }

                // 找到 turn 开始
                Turn turn = new Turn();
                turn.setTurnIndex(turnIdx++);
                turn.setUserMessage(userText);
                i++;

                // 匹配对应的 ModelUsageEntity（按顺序消费）
                ModelUsageEntity matchedUsage = usageIdx < usages.size() ? usages.get(usageIdx) : null;
                List<ToolCallRecord> records = usageIdx < usageToolCalls.size()
                        ? usageToolCalls.get(usageIdx) : Collections.emptyList();
                if (matchedUsage != null) {
                    turn.setInputTokens(matchedUsage.getInputTokens());
                    turn.setOutputTokens(matchedUsage.getOutputTokens());
                    turn.setModelId(matchedUsage.getModelId());
                }
                usageIdx++;

                // 解析此 turn 内的 iterations
                List<Iteration> iterations = new ArrayList<>();
                int recordOffset = 0; // 在 records 列表里的偏移量
                String finalResponse = null;

                while (i < messages.size()) {
                    Message next = messages.get(i);

                    // 遇到下一个有文本的 user 消息 → 新 turn
                    if (next.getRole() == Message.Role.USER) {
                        String nextText = extractUserText(next);
                        if (!nextText.isEmpty()) {
                            break;
                        }
                    }

                    if (next.getRole() == Message.Role.ASSISTANT) {
                        List<ToolUseBlock> toolUses = next.getToolUseBlocks();

                        if (!toolUses.isEmpty()) {
                            // 这是一个有工具调用的 iteration
                            Iteration iter = new Iteration();
                            iter.setIterationIndex(iterations.size());
                            iter.setAssistantText(next.getTextContent());

                            // 收集 tool_result（紧跟的 user 消息）
                            Map<String, ToolResultInfo> resultMap = new HashMap<>();
                            boolean hasToolResultMessage = false;
                            if (i + 1 < messages.size()) {
                                Message maybeResult = messages.get(i + 1);
                                if (maybeResult.getRole() == Message.Role.USER) {
                                    resultMap = extractToolResults(maybeResult);
                                    hasToolResultMessage = !resultMap.isEmpty();
                                }
                            }

                            // 构建 ReplayToolCall 列表
                            List<ReplayToolCall> replayToolCalls = new ArrayList<>();
                            for (ToolUseBlock tu : toolUses) {
                                ReplayToolCall rtc = new ReplayToolCall();
                                rtc.setId(tu.getId());
                                rtc.setName(tu.getName());
                                rtc.setInput(tu.getInput());

                                // 从 tool_result 合并 output + success
                                ToolResultInfo tri = resultMap.get(tu.getId());
                                if (tri != null) {
                                    rtc.setOutput(tri.content);
                                    rtc.setSuccess(!tri.isError);
                                } else {
                                    // 没有对应 tool_result → 取消/中断，标记为失败
                                    rtc.setSuccess(false);
                                }

                                // 从 ToolCallRecord 合并 timing
                                if (recordOffset < records.size()) {
                                    ToolCallRecord rec = records.get(recordOffset);
                                    rtc.setDurationMs(rec.getDurationMs());
                                    rtc.setTimestamp(rec.getTimestamp());
                                    // 如果 record 也有 output 且 rtc 的 output 为空，用 record 的
                                    if (rtc.getOutput() == null && rec.getOutput() != null) {
                                        rtc.setOutput(rec.getOutput());
                                    }
                                    rtc.setSuccess(rec.isSuccess());
                                    recordOffset++;
                                }

                                replayToolCalls.add(rtc);
                            }
                            iter.setToolCalls(replayToolCalls);
                            iterations.add(iter);

                            // 跳过 assistant + 紧跟的 tool_result user 消息
                            i++;
                            if (hasToolResultMessage && i < messages.size()
                                    && messages.get(i).getRole() == Message.Role.USER) {
                                i++;
                            }
                        } else {
                            // 纯文本 assistant 消息 → final response
                            String text = next.getTextContent();
                            if (text != null && !text.isEmpty()) {
                                finalResponse = text;
                            }
                            i++;
                        }
                    } else {
                        // 其他（纯 tool_result user 消息走到这里，跳过）
                        i++;
                    }
                }

                turn.setIterations(iterations);
                turn.setIterationCount(iterations.size());
                turn.setFinalResponse(finalResponse);

                // 计算 turn 总耗时：从第一个 tool 的 timestamp 到最后一个 tool 完成
                if (!records.isEmpty()) {
                    long firstTs = records.get(0).getTimestamp();
                    ToolCallRecord last = records.get(records.size() - 1);
                    long lastEnd = last.getTimestamp() + last.getDurationMs();
                    turn.setDurationMs(lastEnd - firstTs);
                }

                turns.add(turn);
            } else {
                // 非 user 起始（orphan assistant？跳过）
                i++;
            }
        }
        return turns;
    }

    /**
     * 从 user 消息中提取纯文本（忽略 tool_result 块）。
     */
    @SuppressWarnings("unchecked")
    private String extractUserText(Message msg) {
        Object content = msg.getContent();
        if (content instanceof String s) {
            return s.trim();
        }
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object obj : blocks) {
                if (obj instanceof Map<?, ?> map) {
                    if ("text".equals(map.get("type"))) {
                        Object t = map.get("text");
                        if (t != null) {
                            if (!sb.isEmpty()) sb.append("\n");
                            sb.append(t);
                        }
                    }
                } else if (obj instanceof com.skillforge.core.model.ContentBlock block) {
                    if ("text".equals(block.getType()) && block.getText() != null) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(block.getText());
                    }
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    /**
     * 从 user 消息中提取 tool_result 块，按 tool_use_id 索引。
     */
    @SuppressWarnings("unchecked")
    private Map<String, ToolResultInfo> extractToolResults(Message msg) {
        Object content = msg.getContent();
        if (!(content instanceof List<?> blocks)) {
            return Collections.emptyMap();
        }
        Map<String, ToolResultInfo> map = new HashMap<>();
        for (Object obj : blocks) {
            if (obj instanceof Map<?, ?> m) {
                if ("tool_result".equals(m.get("type"))) {
                    String toolUseId = m.get("tool_use_id") != null ? m.get("tool_use_id").toString() : null;
                    if (toolUseId == null) continue;
                    String c = m.get("content") != null ? m.get("content").toString() : null;
                    boolean isError = Boolean.TRUE.equals(m.get("is_error"));
                    map.put(toolUseId, new ToolResultInfo(c, isError));
                }
            } else if (obj instanceof com.skillforge.core.model.ContentBlock block) {
                if ("tool_result".equals(block.getType()) && block.getToolUseId() != null) {
                    map.put(block.getToolUseId(),
                            new ToolResultInfo(block.getContent(), Boolean.TRUE.equals(block.getIsError())));
                }
            }
        }
        return map;
    }

    private List<ToolCallRecord> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ToolCallRecord>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse toolCalls JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private record ToolResultInfo(String content, boolean isError) {}
}
