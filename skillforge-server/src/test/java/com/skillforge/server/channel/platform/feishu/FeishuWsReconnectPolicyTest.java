package com.skillforge.server.channel.platform.feishu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuWsReconnectPolicyTest {

    @Test
    @DisplayName("nextDelayMs should cap at max with jitter range")
    void nextDelayMs_capsWithJitter() {
        FeishuWsReconnectPolicy policy = FeishuWsReconnectPolicy.defaultPolicy();

        long delay = policy.nextDelayMs(12);

        assertThat(delay).isBetween(51_200L, 76_800L);
    }

    @Test
    @DisplayName("nextDelayMs should return deterministic delay when jitter is disabled")
    void nextDelayMs_withoutJitter_isDeterministic() {
        FeishuWsReconnectPolicy policy = new FeishuWsReconnectPolicy(1_000L, 64_000L, 0d);

        assertThat(policy.nextDelayMs(0)).isEqualTo(1_000L);
        assertThat(policy.nextDelayMs(3)).isEqualTo(8_000L);
        assertThat(policy.nextDelayMs(99)).isEqualTo(64_000L);
    }
}
