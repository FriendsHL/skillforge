package com.skillforge.server.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.repository.SessionMessageRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionHistoryRowAuthorityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionMessageRepository repository = mock(SessionMessageRepository.class);
    private final SessionEntity session = new SessionEntity();

    @Test
    void emptyMirrorRequiresNoRowScan() {
        session.setMessagesJson("[]");
        assertThat(verified()).isTrue();
        verifyNoInteractions(repository);
    }

    @Test
    void malformedMirrorFailsBeforeDatabaseAccess() {
        for (String mirror : new String[]{"malformed", "{}", "[null]", "[{}]",
                "[{\"role\":\"not-a-role\"}]"}) {
            session.setMessagesJson(mirror);
            assertThat(verified()).isFalse();
        }
        verifyNoInteractions(repository);
    }

    @Test
    void successfulVerificationIsNotCachedAcrossSameEpochChanges() {
        session.setId("session");
        session.setMessagesJson("[{\"role\":\"user\",\"content\":\"exact fact\"}]");
        when(repository.isLegacyPrefixRepresented(session.getId(), session.getMessagesJson()))
                .thenReturn(true, false);
        assertThat(verified()).isTrue();
        assertThat(verified()).isFalse();
    }

    private boolean verified() {
        return SessionHistoryRowAuthority.isVerified(session, repository, mapper);
    }
}
