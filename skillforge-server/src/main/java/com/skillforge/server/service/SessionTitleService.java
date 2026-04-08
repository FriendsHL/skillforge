package com.skillforge.server.service;

import com.skillforge.core.engine.ChatEventBroadcaster;
import com.skillforge.core.llm.LlmProvider;
import com.skillforge.core.llm.LlmProviderFactory;
import com.skillforge.core.llm.LlmRequest;
import com.skillforge.core.llm.LlmResponse;
import com.skillforge.core.llm.LlmStreamHandler;
import com.skillforge.core.model.Message;
import com.skillforge.core.model.ToolUseBlock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import com.skillforge.server.config.LlmProperties;
import com.skillforge.server.entity.SessionEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 会话标题生成服务(方案 C):
 * - 第一条 user message 立即截断写入 (immediate)
 * - messageCount >= SMART_RENAME_AFTER 后异步调用 LLM 生成 ≤ 10 字精炼标题
 *
 * 幂等保护: SessionEntity.smartTitled 标记避免重复触发。
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    private static final String DEFAULT_TITLE = "New Session";
    private static final int IMMEDIATE_MAX_LEN = 30;
    /** 当 messages 数量达到这个阈值时尝试智能命名(=2 轮对话后) */
    private static final int SMART_RENAME_AFTER = 4;
    private static final int SMART_TITLE_MAX_LEN = 20;

    private final SessionService sessionService;
    private final LlmProviderFactory llmProviderFactory;
    private final LlmProperties llmProperties;
    private final ChatEventBroadcaster broadcaster;
    private final ExecutorService renamerExecutor;

    public SessionTitleService(SessionService sessionService,
                               LlmProviderFactory llmProviderFactory,
                               LlmProperties llmProperties,
                               ChatEventBroadcaster broadcaster) {
        this.sessionService = sessionService;
        this.llmProviderFactory = llmProviderFactory;
        this.llmProperties = llmProperties;
        this.broadcaster = broadcaster;
        this.renamerExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "session-renamer-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        renamerExecutor.shutdown();
        try {
            if (!renamerExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                renamerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 第一条 user message 时立即写入截断标题。仅在当前 title 为空或为默认值时生效。
     */
    public void applyImmediateTitle(String sessionId, String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) {
            return;
        }
        try {
            SessionEntity s = sessionService.getSession(sessionId);
            if (!isDefaultTitle(s.getTitle())) {
                return;
            }
            String title = truncate(firstUserMessage);
            if (title.isBlank()) {
                return;
            }
            s.setTitle(title);
            sessionService.saveSession(s);
            broadcastTitle(sessionId, title);
            log.info("Session title (immediate) set: sessionId={}, title={}", sessionId, title);
        } catch (Exception e) {
            log.warn("applyImmediateTitle failed: sessionId={}", sessionId, e);
        }
    }

    /**
     * 在 message 数量达到阈值后,如果尚未智能命名过则异步触发一次 LLM 重命名。
     *
     * @param messageCountAfter 当前 session 持久化后的 message 数量
     */
    public void maybeScheduleSmartRename(String sessionId, int messageCountAfter) {
        log.info("maybeScheduleSmartRename: sessionId={}, count={}, threshold={}",
                sessionId, messageCountAfter, SMART_RENAME_AFTER);
        if (messageCountAfter < SMART_RENAME_AFTER) {
            log.info("maybeScheduleSmartRename: skip, count below threshold");
            return;
        }
        try {
            SessionEntity s = sessionService.getSession(sessionId);
            if (s.isSmartTitled()) {
                log.info("maybeScheduleSmartRename: skip, already smart-titled");
                return;
            }
        } catch (Exception e) {
            log.warn("maybeScheduleSmartRename pre-check failed: sessionId={}", sessionId, e);
            return;
        }
        log.info("maybeScheduleSmartRename: submitting to renamer executor");
        renamerExecutor.submit(() -> doSmartRename(sessionId));
    }

    private void doSmartRename(String sessionId) {
        log.info("doSmartRename: ENTER sessionId={}", sessionId);
        try {
            SessionEntity s = sessionService.getSession(sessionId);
            log.info("doSmartRename: loaded session, smartTitled={}", s.isSmartTitled());
            if (s.isSmartTitled()) {
                return;
            }
            List<Message> messages = sessionService.getSessionMessages(sessionId);
            if (messages.isEmpty()) {
                return;
            }

            String providerName = llmProperties.getDefaultProvider();
            LlmProvider provider = llmProviderFactory.getProvider(providerName);
            log.info("doSmartRename: provider={}, instance={}", providerName, provider);
            if (provider == null) {
                log.warn("doSmartRename: provider {} not found, skipping", providerName);
                return;
            }

            String summary = buildConversationSummary(messages);
            LlmRequest req = new LlmRequest();
            req.setSystemPrompt("你是一个会话标题生成助手。" +
                    "请根据下面的对话内容,用不超过 10 个汉字直接给出一个简明扼要的中文标题。" +
                    "只输出标题文本本身,不要加引号、不要加标点、不要解释。");
            List<Message> reqMsgs = new ArrayList<>();
            reqMsgs.add(Message.user("以下是一段对话摘要,请生成标题:\n\n" + summary));
            req.setMessages(reqMsgs);
            req.setMaxTokens(40);
            req.setTemperature(0.3);

            log.info("doSmartRename: calling LLM provider.chatStream...");
            // 使用 chatStream 同步等待:OpenAiProvider.chat() 同步版本在某些 endpoint 上不稳定,
            // 改用流式 API 收集 delta + CountDownLatch 等待 onComplete 更可靠。
            StringBuilder textBuf = new StringBuilder();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> errRef = new AtomicReference<>();
            provider.chatStream(req, new LlmStreamHandler() {
                @Override public void onText(String text) {
                    if (text != null) textBuf.append(text);
                }
                @Override public void onToolUse(ToolUseBlock block) { /* unused for title */ }
                @Override public void onComplete(LlmResponse fullResponse) { latch.countDown(); }
                @Override public void onError(Throwable error) {
                    errRef.set(error);
                    latch.countDown();
                }
            });
            if (!latch.await(30, TimeUnit.SECONDS)) {
                log.warn("doSmartRename: LLM stream timeout (30s), abort");
                return;
            }
            if (errRef.get() != null) {
                log.warn("doSmartRename: LLM stream error: {}", errRef.get().getMessage());
                return;
            }
            String raw = textBuf.toString();
            log.info("doSmartRename: LLM returned, raw={}", raw);
            String cleaned = cleanTitle(raw);
            if (cleaned.isBlank()) {
                log.warn("doSmartRename: empty title returned for sessionId={}", sessionId);
                return;
            }

            SessionEntity fresh = sessionService.getSession(sessionId);
            fresh.setTitle(cleaned);
            fresh.setSmartTitled(true);
            sessionService.saveSession(fresh);
            broadcastTitle(sessionId, cleaned);
            log.info("Session title (smart) set: sessionId={}, title={}", sessionId, cleaned);
        } catch (Exception e) {
            log.warn("doSmartRename failed: sessionId={}", sessionId, e);
        }
    }

    private boolean isDefaultTitle(String title) {
        return title == null || title.isBlank() || DEFAULT_TITLE.equals(title);
    }

    private String truncate(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() > IMMEDIATE_MAX_LEN) {
            t = t.substring(0, IMMEDIATE_MAX_LEN) + "…";
        }
        return t;
    }

    private String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = raw;
        // 去掉 reasoning 模型(qwen3.5-plus / deepseek-r1 等)的 <think>...</think> 块
        // 兼容只剩闭合标签的情况(开标签丢失)
        t = t.replaceAll("(?is)<think>.*?</think>", "");
        t = t.replaceAll("(?is).*?</think>", "");
        t = t.replaceAll("(?is)<think>.*", "");
        t = t.trim();
        // 多行时取最后一行非空(模型常先输出推理再给出标题)
        String[] lines = t.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                t = lines[i].trim();
                break;
            }
        }
        // 去掉常见包裹符号
        t = t.replaceAll("^[\"'《\\[【]+", "").replaceAll("[\"'》\\]】]+$", "");
        // 去掉首尾标点
        t = t.replaceAll("^[,。.,!! ]+", "").replaceAll("[,。.,!! ]+$", "");
        // 折叠空白
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > SMART_TITLE_MAX_LEN) {
            t = t.substring(0, SMART_TITLE_MAX_LEN);
        }
        return t;
    }

    private String buildConversationSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(messages.size(), 10);
        for (int i = 0; i < limit; i++) {
            Message m = messages.get(i);
            String text = m.getTextContent();
            if (text == null || text.isBlank()) continue;
            String role = m.getRole() == Message.Role.USER ? "用户" : "助手";
            if (text.length() > 200) {
                text = text.substring(0, 200) + "…";
            }
            sb.append(role).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }

    private void broadcastTitle(String sessionId, String title) {
        if (broadcaster == null) return;
        try {
            broadcaster.sessionTitleUpdated(sessionId, title);
        } catch (Throwable t) {
            // 广播是 best-effort,不让其影响主流程
            log.debug("broadcastTitle skipped: {}", t.getMessage());
        }
    }
}
