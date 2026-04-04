package com.skillforge.core.engine;

import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.model.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文压缩器，当消息历史的 token 数量接近上限时，
 * 将早期对话压缩为摘要以释放上下文空间。
 */
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 100000;
    private static final double COMPACT_THRESHOLD = 0.8;
    private static final int DEFAULT_RECENT_MESSAGES_TO_KEEP = 4;
    private static final int MIN_RECENT_MESSAGES_TO_KEEP = 2;

    private static final String SUMMARY_PROMPT =
            "请将以下对话历史压缩为简洁摘要，保留关键信息、决策和结论。" +
            "只输出摘要内容，不要添加任何前缀或解释。";

    private final LlmProvider llmProvider;
    private final TokenCounter tokenCounter;

    public ContextCompactor(LlmProvider llmProvider, TokenCounter tokenCounter) {
        this.llmProvider = llmProvider;
        this.tokenCounter = tokenCounter;
    }

    /**
     * 检查消息列表是否需要压缩，如果需要则执行压缩。
     *
     * @param messages         当前消息列表
     * @param systemPrompt     系统提示词
     * @param maxContextTokens 最大上下文 token 数，0 或负数表示使用默认值
     * @return 压缩后的消息列表（如果不需要压缩则返回原列表）
     */
    public List<Message> compactIfNeeded(List<Message> messages, String systemPrompt, int maxContextTokens) {
        if (messages == null || messages.size() <= DEFAULT_RECENT_MESSAGES_TO_KEEP) {
            return messages;
        }

        if (maxContextTokens <= 0) {
            maxContextTokens = DEFAULT_MAX_CONTEXT_TOKENS;
        }

        int systemPromptTokens = tokenCounter.countTokens(systemPrompt);
        int messageTokens = tokenCounter.countMessageTokens(messages);
        int totalTokens = systemPromptTokens + messageTokens;

        int threshold = (int) (maxContextTokens * COMPACT_THRESHOLD);
        if (totalTokens < threshold) {
            log.debug("Context tokens ({}) below threshold ({}), no compaction needed", totalTokens, threshold);
            return messages;
        }

        log.info("Context compaction triggered: totalTokens={}, threshold={}, maxContextTokens={}",
                totalTokens, threshold, maxContextTokens);

        return doCompact(messages, systemPrompt, maxContextTokens, DEFAULT_RECENT_MESSAGES_TO_KEEP);
    }

    /**
     * 执行压缩：保留最近 N 条消息，将早期消息压缩为摘要。
     * 如果压缩后仍超标，递减保留消息数再次压缩。
     */
    private List<Message> doCompact(List<Message> messages, String systemPrompt,
                                     int maxContextTokens, int recentToKeep) {
        if (recentToKeep < MIN_RECENT_MESSAGES_TO_KEEP) {
            log.warn("Cannot reduce recent messages below {}, returning with current compaction",
                    MIN_RECENT_MESSAGES_TO_KEEP);
            recentToKeep = MIN_RECENT_MESSAGES_TO_KEEP;
        }

        // 确保不超出消息列表长度
        if (recentToKeep >= messages.size()) {
            return messages;
        }

        // 分割：早期消息 + 最近消息
        int splitIndex = messages.size() - recentToKeep;
        List<Message> earlyMessages = messages.subList(0, splitIndex);
        List<Message> recentMessages = messages.subList(splitIndex, messages.size());

        // 提取早期消息的文本内容
        StringBuilder earlyText = new StringBuilder();
        for (Message msg : earlyMessages) {
            String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "unknown";
            String text = msg.getTextContent();
            if (text != null && !text.isEmpty()) {
                earlyText.append("[").append(role).append("]: ").append(text).append("\n\n");
            }
        }

        if (earlyText.isEmpty()) {
            log.debug("No text content in early messages, skipping compaction");
            return messages;
        }

        // 调用 LLM 生成摘要
        String summary = generateSummary(earlyText.toString());
        if (summary == null || summary.isEmpty()) {
            log.warn("Failed to generate summary, returning original messages");
            return messages;
        }

        // 构建压缩后的消息列表
        Message summaryMessage = new Message();
        summaryMessage.setRole(Message.Role.USER);
        summaryMessage.setContent("[Previous conversation summary]\n" + summary);

        List<Message> compacted = new ArrayList<>();
        compacted.add(summaryMessage);
        compacted.addAll(recentMessages);

        // 检查压缩后的 token 数
        int systemPromptTokens = tokenCounter.countTokens(systemPrompt);
        int compactedTokens = systemPromptTokens + tokenCounter.countMessageTokens(compacted);
        int beforeTokens = systemPromptTokens + tokenCounter.countMessageTokens(messages);

        log.info("Context compacted: {} tokens -> {} tokens (kept {} recent messages)",
                beforeTokens, compactedTokens, recentToKeep);

        // 如果仍然超标且还能减少保留消息数，递归压缩
        int threshold = (int) (maxContextTokens * COMPACT_THRESHOLD);
        if (compactedTokens >= threshold && recentToKeep > MIN_RECENT_MESSAGES_TO_KEEP) {
            log.info("Still above threshold after compaction, reducing recent messages from {} to {}",
                    recentToKeep, recentToKeep - 2);
            return doCompact(compacted, systemPrompt, maxContextTokens, recentToKeep - 2);
        }

        return compacted;
    }

    /**
     * 调用 LLM 生成对话摘要。
     */
    private String generateSummary(String conversationText) {
        try {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt(SUMMARY_PROMPT);
            request.setMessages(Collections.singletonList(Message.user(conversationText)));
            request.setTools(Collections.emptyList());
            request.setMaxTokens(2048);
            request.setTemperature(0.3);

            LlmResponse response = llmProvider.chat(request);
            return response.getContent();
        } catch (Exception e) {
            log.error("Failed to generate conversation summary", e);
            return null;
        }
    }
}
