package com.skillforge.core.compact;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import java.util.List;
import java.util.Map;

/**
 * 简易 token 估算器。
 *
 * <p>不依赖真实 tokenizer (jtokkit 已有但此估算器刻意独立), 用于 context 压缩阈值判断,
 * 精度够用即可。核心近似:
 * <ul>
 *   <li>1 token ≈ 3.5 ASCII 字符 (OpenAI 官方经验值接近 4)</li>
 *   <li>CJK 字符更密, 直接按 1 token/字符近似 (会偏高, 但对"是否压缩"这种阈值判断安全)</li>
 *   <li>每条 message 加 4 token 的 role/结构开销</li>
 * </ul>
 *
 * <p>如果未来需要更精准可切换到 jtokkit, 但那会在压缩检查路径上引入较重计算。
 */
public final class TokenEstimator {

    /** 每条消息固定的角色/结构开销 (role、start/stop marker 等)。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    public static int estimate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message m : messages) {
            total += PER_MESSAGE_OVERHEAD;
            total += estimateContent(m.getContent());
        }
        return total;
    }

    public static int estimateString(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (isCjk(c)) cjk++;
            else other++;
        }
        // CJK ~1 token/char, 其他 3.5 char/token
        return cjk + (int) Math.ceil(other / 3.5);
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

    private static boolean isCjk(char c) {
        // 覆盖 CJK 统一汉字 + 常用日文 + 韩文基本面
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF)
                || (c >= 0x3400 && c <= 0x4DBF);
    }
}
