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
        // 跳过结构化/技术类 prompt：以 * # { [ 开头，或内容过短（< 10 字符）
        // 这类消息通常是 eval 任务指令，截断后标题无意义；等 smart rename 接管
        String trimmed = firstUserMessage.strip();
        if (trimmed.length() < 10 || trimmed.matches("^[*#\\[{].*")) {
            log.debug("applyImmediateTitle: skipped structured/short message, sessionId={}", sessionId);
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
                    "只输出标题文本本身,不要加引号、不要加标点、不要解释。" +
                    "如果对话内容是技术任务（如代码生成、评测、数据分析等英文指令），" +
                    "请描述任务类型而不是复述指令原文，例如「代码评测任务」「场景分析」「Agent 调试」。");
            List<Message> reqMsgs = new ArrayList<>();
            reqMsgs.add(Message.user("以下是一段对话摘要,请生成标题:\n\n" + summary));
            req.setMessages(reqMsgs);
            req.setMaxTokens(40);
            req.setTemperature(0.3);
            // 2026-05-24 fix: title 这种简单任务一律 disable thinking — 防御任何
            // 当前/未来 reasoning model（mimo / qwen3-think / o1 等）的思考链
            // 被 onText 累积导致 title = "首先，用户要求我..." 思考独白。
            // 根因 fix 在 ProviderProtocolFamilyResolver PREFIX 加 mimo，
            // 此处是兜底防御未注册 reasoning model + reasoning_effort 也设最低。
            // 2026-05-25 升级: doSmartRename 改用 LlmResponse.getContent() 不再走 onText 累积
            // (见 doSmartRename 内注释)；本 ThinkingMode.DISABLED 仍保留以节省上游推理 cost/latency。
            req.setThinkingMode(com.skillforge.core.model.ThinkingMode.DISABLED);

            log.info("doSmartRename: calling LLM provider.chatStream...");
            // 使用 chatStream 同步等待:OpenAiProvider.chat() 同步版本在某些 endpoint 上不稳定,
            // 改用流式 API + CountDownLatch 等待 onComplete 更可靠。
            //
            // 用 fullResponse.getContent() 而非 onText 累积:
            // OpenAiProvider.handleStreamingResponse 把 reasoning_content delta 跟 content delta
            // 都走 handler.onText(...) (前端要流式渲染思考过程是有意设计, 见 OpenAiProvider.java:1023)。
            // onComplete 时 OpenAiProvider 把两路分别填到 setContent / setReasoningContent (line ~1090),
            // 这里直接取 getContent() 就只拿真 content, 不会被思考链污染。
            // 防御所有 reasoning model 不论上游是否真响应 enable_thinking:false。
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Throwable> errRef = new AtomicReference<>();
            AtomicReference<LlmResponse> respRef = new AtomicReference<>();
            provider.chatStream(req, new LlmStreamHandler() {
                @Override public void onText(String text) {
                    // intentionally empty: title only consumes finalized content from
                    // onComplete to avoid reasoning_content pollution (see comment above).
                }
                @Override public void onToolUse(ToolUseBlock block) { /* unused for title */ }
                @Override public void onComplete(LlmResponse fullResponse) {
                    respRef.set(fullResponse);
                    latch.countDown();
                }
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
            LlmResponse resp = respRef.get();
            // content-first + reasoning fallback: mimo / qwen-think 等 reasoning model 即便
            // enable_thinking:false 仍把全部输出走 reasoning_content 通道，content 通道为空 ——
            // 直接取 getContent() 会拿到空 title。fallback 到 reasoning_content 让 cleanTitle 的
            // while-loop 剥裸推理前缀 + 取末尾段逻辑兜底。
            // 走 content 通道的 provider (claude / openai / deepseek-chat 等) 仍优先用纯净 content。
            String raw = pickRawForTitle(resp);
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

    /**
     * Content-first + reasoning fallback. Package-private so tests can exercise the
     * provider-shape branching directly without mocking the full chatStream loop.
     *
     * <p>Why: mimo / qwen-think etc. reasoning models with {@code enable_thinking:false} still
     * route the entire output into {@code reasoning_content}; {@code content} stays empty.
     * Using only {@code getContent()} yields an empty title in that case. Falling back to
     * {@code reasoning_content} lets {@link #cleanTitle(String)}'s while-loop strip the
     * "首先 / 让我 / 用户要求…" prefixes and keep the trailing title segment.
     */
    String pickRawForTitle(LlmResponse resp) {
        if (resp == null) return "";
        String content = resp.getContent();
        if (content != null && !content.isBlank()) return content;
        String reasoning = resp.getReasoningContent();
        return reasoning != null ? reasoning : "";
    }

    /**
     * Package-private (only) so {@code SessionTitleServiceCleanTitleTest} can exercise the
     * regex / fallback logic directly. Not part of the public API.
     */
    String cleanTitle(String raw) {
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
        // 兜底: 即使 <think> 没标签的裸推理文本也要剥掉。reasoning model 末尾常输出
        // "标题：XX" 或单独一行 XX, 优先取最后一个句末符号后的非空片段; 找不到 break
        // 或 tail 为空时也必须**至少剥掉 prefix 本身** —— 用户感知 bug 就是 title 以
        // "首先，用户要求..." 开头, prefix 不剥等于没修。
        //
        // 嵌套 prefix（如"首先让我..."=首先+让我）必须反复剥 —— while 循环每次 changed=true
        // 保证 t 严格变短(剥 prefix ≥2 chars 或 substring 到 break 后)，最坏 O(prefixes.length × t.length())
        // 收敛。
        String[] reasoningPrefixes = {"首先", "用户要求", "让我", "我需要", "好的，", "好的,", "嗯，", "嗯,", "<think"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : reasoningPrefixes) {
                if (t.startsWith(prefix)) {
                    int lastBreak = -1;
                    for (int i = t.length() - 1; i >= 0; i--) {
                        char c = t.charAt(i);
                        if (c == '。' || c == '.' || c == '\n' || c == '：' || c == ':') {
                            lastBreak = i;
                            break;
                        }
                    }
                    boolean tailUsed = false;
                    if (lastBreak >= 0 && lastBreak < t.length() - 1) {
                        String tail = t.substring(lastBreak + 1).trim();
                        if (!tail.isEmpty()) {
                            t = tail;
                            tailUsed = true;
                        }
                    }
                    if (!tailUsed) {
                        // 没 break char (或 tail 为空) —— 至少剥 prefix
                        // 例:"首先让我分析..." → 一轮剥 "首先" → "让我分析..." → 二轮剥 "让我" → "分析..."
                        t = t.substring(prefix.length()).trim();
                    }
                    changed = true;
                    break;   // 重启 prefixes 扫描（已 changed）
                }
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
        // Per-user 通道:列表页实时刷新该 session 的标题
        try {
            SessionEntity s = sessionService.getSession(sessionId);
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "session_updated");
            payload.put("sessionId", sessionId);
            payload.put("title", title);
            payload.put("runtimeError", s.getRuntimeError());
            payload.put("failureSource", s.getRuntimeFailureSource());
            payload.put("failureCode", s.getRuntimeFailureCode());
            payload.put("retryable", s.isRuntimeRetryable());
            payload.put("sideEffects", s.getRuntimeSideEffects());
            payload.put("updatedAt", s.getUpdatedAt());
            broadcaster.userEvent(s.getUserId(), payload);
        } catch (Throwable t) {
            log.debug("broadcastTitle user-event skipped: {}", t.getMessage());
        }
    }
}
