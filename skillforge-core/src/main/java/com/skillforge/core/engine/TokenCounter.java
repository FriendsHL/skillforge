package com.skillforge.core.engine;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;

import java.util.List;
import java.util.Map;

/**
 * Token 计数工具类，使用 jtokkit 的 cl100k_base 编码计算 token 数量。
 */
public class TokenCounter {

    private static final int TOKENS_PER_MESSAGE_OVERHEAD = 4;

    private final Encoding encoding;

    public TokenCounter() {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    /**
     * 计算单段文本的 token 数。
     */
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * 计算消息列表的总 token 数。
     * 每条消息包含角色开销（约 4 tokens）加上内容的 token 数。
     */
    public int countMessageTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += TOKENS_PER_MESSAGE_OVERHEAD;
            total += countMessageContentTokens(message);
        }
        return total;
    }

    /**
     * 计算单条消息内容的 token 数。
     * 支持纯文本内容和 ContentBlock 列表（包括 text、tool_use、tool_result）。
     */
    private int countMessageContentTokens(Message message) {
        Object content = message.getContent();

        if (content instanceof String text) {
            return countTokens(text);
        }

        if (content instanceof List<?> blocks) {
            int total = 0;
            for (Object obj : blocks) {
                if (obj instanceof ContentBlock block) {
                    total += countContentBlockTokens(block);
                }
            }
            return total;
        }

        return 0;
    }

    /**
     * 计算单个 ContentBlock 的 token 数。
     */
    private int countContentBlockTokens(ContentBlock block) {
        String type = block.getType();
        if (type == null) {
            return 0;
        }

        switch (type) {
            case "text":
                return countTokens(block.getText());

            case "tool_use":
                int tokens = countTokens(block.getName());
                if (block.getInput() != null) {
                    tokens += countTokens(block.getInput().toString());
                }
                return tokens;

            case "tool_result":
                return countTokens(block.getContent());

            default:
                return 0;
        }
    }
}
