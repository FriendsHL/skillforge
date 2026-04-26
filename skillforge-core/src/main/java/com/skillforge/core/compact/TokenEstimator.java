package com.skillforge.core.compact;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Token 估算器（cl100k_base 底盘）。
 *
 * <p>使用 jtokkit cl100k_base encoding 计算 token 数。jtokkit 的 {@link Encoding}
 * 实现是无状态的、线程安全的（README 明示），因此整个进程共享一个 {@code static final}
 * 单例足够。
 *
 * <p>性能优化：因为压缩检查 / 上下文破解会反复对同一条 {@link Message} 重复计算 token，
 * 我们用 identity-based 缓存：{@code WeakHashMap<Message, Integer>}（被
 * {@link Collections#synchronizedMap} 包装防并发）。WeakHashMap 在 Message 对象没有
 * 其他强引用时会自动回收 entry，避免内存泄漏；{@link Message} 没有重写 equals/hashCode，
 * 因此 WeakHashMap 退化为 identity 比较，正好是我们想要的"同一条消息引用命中缓存"语义。
 *
 * <p>缓存的是 content token 数（不含 PER_MESSAGE_OVERHEAD），overhead 在
 * {@link #estimate(List)} 内每条消息额外加一次，避免遗漏或重复。
 *
 * <p>API 保持兼容：4 个 public/private static 方法签名不变，调用点零改动。
 */
public final class TokenEstimator {

    /** 每条消息固定的角色/结构开销 (role、start/stop marker 等)。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /** jtokkit cl100k_base encoding 单例（线程安全）。 */
    private static final Encoding CL100K;

    static {
        // 用 lazy registry：仅在 getEncoding 首次调用时加载 cl100k_base 词表，
        // 避免 default registry 顺带加载 r50k/p50k/o200k 三套用不到的编码表（~30MB）。
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        CL100K = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * Identity-based per-Message 缓存。WeakHashMap 在 Message 不再被强引用时自动 GC。
     * 用 {@link Collections#synchronizedMap} 包装以支持多线程压缩 / 估算并发；包装后
     * 仍保留 weak 语义（仅是同步外壳，不影响 ReferenceQueue 回收）。
     */
    private static final Map<Message, Integer> CONTENT_TOKEN_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private TokenEstimator() {
    }

    public static int estimate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message m : messages) {
            if (m == null) continue;
            total += PER_MESSAGE_OVERHEAD;
            total += contentTokensFor(m);
        }
        return total;
    }

    public static int estimateString(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        // countTokensOrdinary 对包含特殊 token 文本（如 "<|endoftext|>"）不抛异常，
        // 而是按普通文本编码。Tool 输出 / 用户内容里出现这种字符串是合法的，所以选 ordinary。
        return CL100K.countTokensOrdinary(s);
    }

    /** 取该 Message 的 content token 数，命中缓存就直接返回。 */
    private static int contentTokensFor(Message m) {
        Integer cached = CONTENT_TOKEN_CACHE.get(m);
        if (cached != null) {
            return cached;
        }
        int v = estimateContent(m.getContent());
        CONTENT_TOKEN_CACHE.put(m, v);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static int estimateContent(Object content) {
        if (content == null) return 0;
        if (content instanceof String s) {
            return estimateString(s);
        }
        if (content instanceof List<?> blocks) {
            int total = 0;
            for (Object o : blocks) {
                if (o instanceof ContentBlock b) {
                    total += estimateBlock(b);
                } else if (o instanceof Map<?, ?> m) {
                    Object text = m.get("text");
                    if (text != null) total += estimateString(text.toString());
                    Object bc = m.get("content");
                    if (bc != null) total += estimateString(bc.toString());
                    Object input = m.get("input");
                    if (input != null) total += estimateString(input.toString());
                }
            }
            return total;
        }
        return estimateString(content.toString());
    }

    private static int estimateBlock(ContentBlock b) {
        String type = b.getType();
        if (type == null) return 0;
        switch (type) {
            case "text":
                return estimateString(b.getText());
            case "tool_use": {
                int t = estimateString(b.getName());
                if (b.getInput() != null) {
                    t += estimateString(b.getInput().toString());
                }
                return t;
            }
            case "tool_result":
                return estimateString(b.getContent());
            default:
                return 0;
        }
    }
}
