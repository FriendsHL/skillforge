package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalAppLibraryServiceTest {

    private static final String TOKEN = "mobile-device-token-for-library-tests";
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");

    private PersonalAppLibraryRepository repository;
    private PersonalAppCursorCodec cursorCodec;
    private PersonalAppLibraryService service;

    @BeforeEach
    void setUp() {
        repository = mock(PersonalAppLibraryRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        cursorCodec = new PersonalAppCursorCodec(objectMapper);
        service = new PersonalAppLibraryService(
                repository,
                objectMapper,
                cursorCodec,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listMapsOnlyAllowlistedManifestPresentationFieldsAndUsesDefaultLimit() {
        when(repository.list(any())).thenReturn(List.of(row("artifact-1", NOW, null)));

        MobilePersonalAppListResponse response = service.list(
                7L, TOKEN, new PersonalAppListRequest(null, null, null, null, null, null, null, null));

        assertThat(response.nextCursor()).isNull();
        assertThat(response.items()).containsExactly(new MobilePersonalAppItemResponse(
                "artifact-1",
                "session-1",
                12L,
                "Daily Brief",
                "server caption",
                1,
                List.of(),
                List.of(),
                9L,
                "Main Assistant",
                "Morning research",
                NOW.minusSeconds(60),
                null,
                false,
                "available"));
        var query = org.mockito.ArgumentCaptor.forClass(PersonalAppLibraryRepository.ListQuery.class);
        verify(repository).list(query.capture());
        assertThat(query.getValue().limit()).isEqualTo(21);
        assertThat(query.getValue().sort()).isEqualTo("recent");
    }

    @Test
    void blankCaptionUsesManifestFallbackAndMalformedRowsDoNotFailPage() {
        PersonalAppLibraryRepository.PersonalAppRow fallback = row("artifact-fallback", NOW, null);
        fallback = new PersonalAppLibraryRepository.PersonalAppRow(
                fallback.artifactId(), fallback.sessionId(), fallback.sourceMessageSeq(),
                fallback.filename(), "  ", fallback.manifestJson(), fallback.agentId(),
                fallback.agentName(), fallback.sessionTitle(), fallback.createdAt(),
                fallback.lastOpenedAt(), fallback.favorite(), fallback.sortTimestamp());
        PersonalAppLibraryRepository.PersonalAppRow malformed =
                new PersonalAppLibraryRepository.PersonalAppRow(
                        "artifact-malformed", "session-1", 13L, "malformed.html", null,
                        "{not-json", 9L, "Main Assistant", "Morning research",
                        NOW.minusSeconds(120), null, false, NOW.minusSeconds(120));
        when(repository.list(any())).thenReturn(List.of(fallback, malformed));

        MobilePersonalAppListResponse response = service.list(
                7L, TOKEN, new PersonalAppListRequest(null, null, null, null, null, null, null, null));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).caption()).isEqualTo("Open the safe fallback");
        assertThat(response.items().get(0).artifactId()).isEqualTo("artifact-fallback");
    }

    @Test
    void listKeepsOwnedPublishedLegacyManifestWithUnsupportedSnapshotKeywords() {
        PersonalAppLibraryRepository.PersonalAppRow legacy = row("artifact-legacy", NOW, null);
        legacy = new PersonalAppLibraryRepository.PersonalAppRow(
                legacy.artifactId(), legacy.sessionId(), legacy.sourceMessageSeq(),
                legacy.filename(), legacy.caption(),
                "{\"schemaVersion\":1,\"title\":\"Legacy Tracker\","
                        + "\"fallback\":\"Open the existing tracker\","
                        + "\"permissions\":[],\"network\":[],\"initialData\":{},"
                        + "\"stateSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"status\":{\"type\":\"string\",\"enum\":[\"open\",\"closed\"]},"
                        + "\"score\":{\"type\":\"number\",\"minimum\":0,\"maximum\":5}}}}",
                legacy.agentId(), legacy.agentName(), legacy.sessionTitle(), legacy.createdAt(),
                legacy.lastOpenedAt(), legacy.favorite(), legacy.sortTimestamp());
        when(repository.list(any())).thenReturn(List.of(legacy));

        MobilePersonalAppListResponse response = service.list(
                7L, TOKEN, new PersonalAppListRequest(null, null, null, null, null, null, null, null));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.artifactId()).isEqualTo("artifact-legacy");
            assertThat(item.title()).isEqualTo("Legacy Tracker");
            assertThat(item.availability()).isEqualTo("available");
        });
    }

    @Test
    void listStillDropsLegacyManifestThatRequestsUnavailableCapabilities() {
        PersonalAppLibraryRepository.PersonalAppRow unsafe = row("artifact-unsafe", NOW, null);
        unsafe = new PersonalAppLibraryRepository.PersonalAppRow(
                unsafe.artifactId(), unsafe.sessionId(), unsafe.sourceMessageSeq(),
                unsafe.filename(), unsafe.caption(),
                "{\"schemaVersion\":1,\"title\":\"Unsafe\",\"fallback\":\"Unsafe\","
                        + "\"permissions\":[\"camera\"],\"network\":[],\"initialData\":{},"
                        + "\"stateSchema\":{\"type\":\"object\"}}",
                unsafe.agentId(), unsafe.agentName(), unsafe.sessionTitle(), unsafe.createdAt(),
                unsafe.lastOpenedAt(), unsafe.favorite(), unsafe.sortTimestamp());
        when(repository.list(any())).thenReturn(List.of(unsafe));

        MobilePersonalAppListResponse response = service.list(
                7L, TOKEN, new PersonalAppListRequest(null, null, null, null, null, null, null, null));

        assertThat(response.items()).isEmpty();
    }

    @Test
    void keysetCursorUsesLastVisibleItemAndIsRejectedWhenFiltersChange() {
        List<PersonalAppLibraryRepository.PersonalAppRow> rows = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            rows.add(row("artifact-" + String.format("%02d", i), NOW.minusSeconds(i), null));
        }
        when(repository.list(any())).thenReturn(rows);

        PersonalAppListRequest firstRequest = new PersonalAppListRequest(
                null, "20", "recent", "brief", "9", "session-1", "true",
                "2026-07-01T00:00:00Z");
        MobilePersonalAppListResponse first = service.list(7L, TOKEN, firstRequest);

        assertThat(first.items()).hasSize(20);
        assertThat(first.nextCursor()).isNotBlank();
        PersonalAppCursorCodec.Cursor decoded = cursorCodec.decode(TOKEN, first.nextCursor());
        assertThat(decoded.sortTimestamp()).isEqualTo(NOW.minusSeconds(19));
        assertThat(decoded.artifactId()).isEqualTo("artifact-19");

        when(repository.list(any())).thenReturn(List.of());
        PersonalAppListRequest secondRequest = new PersonalAppListRequest(
                first.nextCursor(), "20", "recent", "brief", "9", "session-1", "true",
                "2026-07-01T00:00:00Z");
        service.list(7L, TOKEN, secondRequest);

        var queries = org.mockito.ArgumentCaptor.forClass(PersonalAppLibraryRepository.ListQuery.class);
        verify(repository, org.mockito.Mockito.times(2)).list(queries.capture());
        assertThat(queries.getAllValues().get(1).cursorTimestamp()).isEqualTo(NOW.minusSeconds(19));
        assertThat(queries.getAllValues().get(1).cursorArtifactId()).isEqualTo("artifact-19");

        PersonalAppListRequest changedFilter = new PersonalAppListRequest(
                first.nextCursor(), "20", "recent", "different", "9", "session-1", "true",
                "2026-07-01T00:00:00Z");
        assertBadRequest(() -> service.list(7L, TOKEN, changedFilter));
    }

    @Test
    void schemaInvalidRawPageKeepsCursorSoClientsCanAdvanceToLaterValidRows() {
        List<PersonalAppLibraryRepository.PersonalAppRow> rows = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            PersonalAppLibraryRepository.PersonalAppRow valid = row(
                    "invalid-" + String.format("%02d", i),
                    NOW.minusSeconds(i),
                    null);
            rows.add(new PersonalAppLibraryRepository.PersonalAppRow(
                    valid.artifactId(), valid.sessionId(), valid.sourceMessageSeq(),
                    valid.filename(), valid.caption(), "{}", valid.agentId(),
                    valid.agentName(), valid.sessionTitle(), valid.createdAt(),
                    valid.lastOpenedAt(), valid.favorite(), valid.sortTimestamp()));
        }
        when(repository.list(any())).thenReturn(rows);

        MobilePersonalAppListResponse response = service.list(
                7L,
                TOKEN,
                new PersonalAppListRequest(null, "20", "recent", null, null, null, null, null));

        assertThat(response.items()).isEmpty();
        assertThat(response.nextCursor()).isNotBlank();
        PersonalAppCursorCodec.Cursor decoded = cursorCodec.decode(TOKEN, response.nextCursor());
        assertThat(decoded.sortTimestamp()).isEqualTo(NOW.minusSeconds(19));
        assertThat(decoded.artifactId()).isEqualTo("invalid-19");
    }

    @Test
    void listRejectsMalformedOrOutOfRangeInputs() {
        List<PersonalAppListRequest> invalid = List.of(
                request("0", "recent", null, null, null, null, null),
                request("51", "recent", null, null, null, null, null),
                request("20", "oldest", null, null, null, null, null),
                request("20", "recent", null, "0", null, null, null),
                request("20", "recent", null, "abc", null, null, null),
                request("20", "recent", null, null, "bad/session", null, null),
                request("20", "recent", null, null, null, "TRUE", null),
                request("20", "recent", null, null, null, null, "2026-07-17"),
                request("20", "recent", "x".repeat(201), null, null, null, null),
                request("20", "recent", "bad\u0001query", null, null, null, null));

        for (PersonalAppListRequest request : invalid) {
            assertBadRequest(() -> service.list(7L, TOKEN, request));
        }
        assertBadRequest(() -> service.list(0L, TOKEN, request("20", "recent", null, null, null, null, null)));
    }

    @Test
    void preferenceMutationsAreGuardedAndUseOneNotFoundShape() {
        PersonalAppLibraryRepository.PreferenceRow row =
                new PersonalAppLibraryRepository.PreferenceRow("artifact-1", true, NOW);
        when(repository.setFavorite(7L, "artifact-1", true, NOW)).thenReturn(Optional.of(row));
        when(repository.markOpened(7L, "artifact-1", NOW)).thenReturn(Optional.of(row));

        assertThat(service.setFavorite(7L, "artifact-1", true))
                .isEqualTo(new MobilePersonalAppPreferenceResponse("artifact-1", true, NOW));
        assertThat(service.markOpened(7L, "artifact-1"))
                .isEqualTo(new MobilePersonalAppPreferenceResponse("artifact-1", true, NOW));

        when(repository.setFavorite(7L, "missing", false, NOW)).thenReturn(Optional.empty());
        when(repository.markOpened(7L, "missing", NOW)).thenReturn(Optional.empty());
        assertNotFound(() -> service.setFavorite(7L, "missing", false));
        assertNotFound(() -> service.markOpened(7L, "missing"));
        assertBadRequest(() -> service.setFavorite(7L, "bad/path", false));
    }

    private static PersonalAppLibraryRepository.PersonalAppRow row(
            String artifactId, Instant sortTimestamp, Instant lastOpenedAt) {
        return new PersonalAppLibraryRepository.PersonalAppRow(
                artifactId,
                "session-1",
                12L,
                "brief.html",
                "server caption",
                "{\"schemaVersion\":1,\"title\":\"Daily Brief\","
                        + "\"fallback\":\"Open the safe fallback\",\"permissions\":[],\"network\":[],"
                        + "\"initialData\":{\"secret\":\"never on list wire\"},"
                        + "\"stateSchema\":{\"type\":\"object\"}}",
                9L,
                "Main Assistant",
                "Morning research",
                NOW.minusSeconds(60),
                lastOpenedAt,
                false,
                sortTimestamp);
    }

    private static PersonalAppListRequest request(
            String limit, String sort, String q, String agentId, String sessionId,
            String favorite, String createdAfter) {
        return new PersonalAppListRequest(
                null, limit, sort, q, agentId, sessionId, favorite, createdAfter);
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
