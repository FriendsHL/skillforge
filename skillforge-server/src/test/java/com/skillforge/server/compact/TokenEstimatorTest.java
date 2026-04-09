package com.skillforge.server.compact;

import com.skillforge.core.compact.TokenEstimator;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenEstimator 是一个粗略估算器, 用 chars/3.5 + CJK 每字符 1 token + 每消息 4 开销。
 * 精度不重要, 只保证方向性正确 (空列表 → 0, 更长的文本 → 更多 token)。
 */
class TokenEstimatorTest {

    @Test
    void empty_list_returns_zero() {
        assertThat(TokenEstimator.estimate(List.of())).isEqualTo(0);
    }

    @Test
    void english_sample_is_close_to_chars_over_four() {
        // "Hello world, this is a sample sentence." = 39 chars
        // approx: ceil(39 / 3.5) = 12 content + 4 overhead = 16
        String text = "Hello world, this is a sample sentence.";
        int est = TokenEstimator.estimate(List.of(Message.user(text)));
        // sanity: within a wide band around chars/4
        assertThat(est).isBetween(text.length() / 5, text.length());
    }
}
