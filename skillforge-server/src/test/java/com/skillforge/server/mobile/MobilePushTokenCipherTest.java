package com.skillforge.server.mobile;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class MobilePushTokenCipherTest {
    @Test
    void encrypt_roundTripsWithoutPersistingPlaintext() {
        MobilePushTokenCipher cipher = new MobilePushTokenCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String raw = "ab".repeat(32);
        String protectedValue = cipher.encrypt(raw);
        assertThat(protectedValue).doesNotContain(raw);
        assertThat(cipher.decrypt(protectedValue)).isEqualTo(raw);
        assertThat(cipher.hash(raw)).hasSize(64);
    }

    @Test
    void missingKey_failsClosed() {
        MobilePushTokenCipher cipher = new MobilePushTokenCipher("");
        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("ab".repeat(32)))
                .isInstanceOf(IllegalStateException.class);
    }
}
