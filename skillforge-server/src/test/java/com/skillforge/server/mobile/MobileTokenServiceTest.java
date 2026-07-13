package com.skillforge.server.mobile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MobileTokenService")
class MobileTokenServiceTest {

    private final MobileTokenService tokenService = new MobileTokenService();

    @Test
    @DisplayName("hashToken returns deterministic lowercase SHA-256 hex")
    void hashToken_returnsDeterministicSha256Hex() {
        String hash = tokenService.hashToken("device-token");

        assertThat(hash)
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(tokenService.hashToken("device-token"));
        assertThat(hash).isNotEqualTo("device-token");
    }

    @Test
    @DisplayName("matchesToken compares raw token against stored hash")
    void matchesToken_comparesRawTokenAgainstStoredHash() {
        String hash = tokenService.hashToken("pairing-secret");

        assertThat(tokenService.matchesToken("pairing-secret", hash)).isTrue();
        assertThat(tokenService.matchesToken("wrong-secret", hash)).isFalse();
        assertThat(tokenService.matchesToken(null, hash)).isFalse();
        assertThat(tokenService.matchesToken("pairing-secret", null)).isFalse();
    }

    @Test
    @DisplayName("newToken returns URL-safe high-entropy tokens")
    void newToken_returnsUrlSafeHighEntropyTokens() {
        String first = tokenService.newToken();
        String second = tokenService.newToken();

        assertThat(first)
                .isNotBlank()
                .doesNotContain("=")
                .matches("[A-Za-z0-9_-]+");
        assertThat(second).isNotEqualTo(first);
    }
}
