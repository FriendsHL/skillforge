package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PersonalAppCursorCodecTest {

    private static final String TOKEN_A = "device-token-a-with-enough-entropy";
    private static final String TOKEN_B = "device-token-b-with-enough-entropy";
    private static final PersonalAppCursorCodec.Cursor CURSOR =
            new PersonalAppCursorCodec.Cursor(
                    Instant.parse("2026-07-17T02:00:00Z"), "artifact-42", "filters-v1");

    @Test
    void roundTripSurvivesFreshCodecInstanceForSameDeviceToken() {
        String encoded = codec().encode(TOKEN_A, CURSOR);

        assertThat(codec().decode(TOKEN_A, encoded)).isEqualTo(CURSOR);
    }

    @Test
    void tamperingIsRejectedWithoutSignatureDetails() {
        String encoded = codec().encode(TOKEN_A, CURSOR);
        int changeAt = encoded.length() / 3;
        char replacement = encoded.charAt(changeAt) == 'A' ? 'B' : 'A';
        String tampered = encoded.substring(0, changeAt) + replacement + encoded.substring(changeAt + 1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec().decode(TOKEN_A, tampered))
                .withMessage("Invalid cursor");
    }

    @Test
    void cursorIsBoundToAuthenticatedDeviceToken() {
        String encoded = codec().encode(TOKEN_A, CURSOR);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec().decode(TOKEN_B, encoded))
                .withMessage("Invalid cursor");
    }

    @Test
    void malformedAndOversizedCursorsFailClosed() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec().decode(TOKEN_A, "not-a-cursor"))
                .withMessage("Invalid cursor");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> codec().decode(TOKEN_A, "a".repeat(4097)))
                .withMessage("Invalid cursor");
    }

    private static PersonalAppCursorCodec codec() {
        return new PersonalAppCursorCodec(new ObjectMapper());
    }
}
