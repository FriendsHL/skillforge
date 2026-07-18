package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.artifact.InteractiveArtifactManifest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PersonalAppLibraryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_QUERY_CODE_POINTS = 200;
    private static final Pattern SAFE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,35}");

    private final PersonalAppLibraryRepository repository;
    private final ObjectMapper objectMapper;
    private final PersonalAppCursorCodec cursorCodec;
    private final Clock clock;

    @Autowired
    public PersonalAppLibraryService(
            PersonalAppLibraryRepository repository,
            ObjectMapper objectMapper,
            PersonalAppCursorCodec cursorCodec) {
        this(repository, objectMapper, cursorCodec, Clock.systemUTC());
    }

    PersonalAppLibraryService(
            PersonalAppLibraryRepository repository,
            ObjectMapper objectMapper,
            PersonalAppCursorCodec cursorCodec,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MobilePersonalAppListResponse list(
            long userId, String bearerToken, PersonalAppListRequest rawRequest) {
        if (userId <= 0 || bearerToken == null || bearerToken.isBlank()) {
            throw badRequest();
        }
        NormalizedRequest request = normalize(rawRequest);
        String binding = filterBinding(request);
        Instant cursorTimestamp = null;
        String cursorArtifactId = null;
        if (request.cursor() != null) {
            PersonalAppCursorCodec.Cursor cursor;
            try {
                cursor = cursorCodec.decode(bearerToken, request.cursor());
            } catch (IllegalArgumentException e) {
                throw badRequest();
            }
            if (!MessageDigest.isEqual(
                    binding.getBytes(StandardCharsets.UTF_8),
                    cursor.filterBinding().getBytes(StandardCharsets.UTF_8))) {
                throw badRequest();
            }
            cursorTimestamp = cursor.sortTimestamp();
            cursorArtifactId = cursor.artifactId();
        }

        List<PersonalAppLibraryRepository.PersonalAppRow> rows = repository.list(
                new PersonalAppLibraryRepository.ListQuery(
                        userId,
                        request.sort(),
                        request.q(),
                        request.agentId(),
                        request.sessionId(),
                        request.favorite(),
                        request.createdAfter(),
                        cursorTimestamp,
                        cursorArtifactId,
                        request.limit() + 1));

        boolean hasMore = rows.size() > request.limit();
        List<PersonalAppLibraryRepository.PersonalAppRow> pageRows = hasMore
                ? rows.subList(0, request.limit()) : rows;
        List<MobilePersonalAppItemResponse> items = new ArrayList<>(pageRows.size());
        PersonalAppLibraryRepository.PersonalAppRow lastConsumed = null;
        for (PersonalAppLibraryRepository.PersonalAppRow row : pageRows) {
            lastConsumed = row;
            MobilePersonalAppItemResponse item = toResponse(row);
            if (item != null) items.add(item);
        }

        String nextCursor = null;
        if (hasMore && lastConsumed != null) {
            nextCursor = cursorCodec.encode(bearerToken, new PersonalAppCursorCodec.Cursor(
                    lastConsumed.sortTimestamp(), lastConsumed.artifactId(), binding));
        }
        return new MobilePersonalAppListResponse(List.copyOf(items), nextCursor);
    }

    @Transactional
    public MobilePersonalAppPreferenceResponse setFavorite(
            long userId, String artifactId, boolean favorite) {
        requireUserAndArtifact(userId, artifactId);
        return repository.setFavorite(userId, artifactId, favorite, clock.instant())
                .map(PersonalAppLibraryService::toResponse)
                .orElseThrow(PersonalAppLibraryService::notFound);
    }

    @Transactional
    public MobilePersonalAppPreferenceResponse markOpened(long userId, String artifactId) {
        requireUserAndArtifact(userId, artifactId);
        return repository.markOpened(userId, artifactId, clock.instant())
                .map(PersonalAppLibraryService::toResponse)
                .orElseThrow(PersonalAppLibraryService::notFound);
    }

    private MobilePersonalAppItemResponse toResponse(
            PersonalAppLibraryRepository.PersonalAppRow row) {
        if (row == null || row.sortTimestamp() == null || row.createdAt() == null) return null;
        try {
            byte[] manifestBytes = row.manifestJson().getBytes(StandardCharsets.UTF_8);
            if (manifestBytes.length > PersonalAppLibraryRepository.MAX_MANIFEST_BYTES) return null;
            InteractiveArtifactManifest manifest = objectMapper.readValue(
                    manifestBytes, InteractiveArtifactManifest.class);
            if (!isSafeLibraryManifest(manifest)) return null;
            String caption = row.caption();
            if (caption == null || caption.isBlank()) caption = manifest.fallback();
            return new MobilePersonalAppItemResponse(
                    row.artifactId(),
                    row.sessionId(),
                    row.sourceMessageSeq(),
                    manifest.title(),
                    caption,
                    manifest.schemaVersion(),
                    manifest.permissions(),
                    manifest.network(),
                    row.agentId(),
                    row.agentName(),
                    row.sessionTitle(),
                    row.createdAt(),
                    row.lastOpenedAt(),
                    row.favorite(),
                    "available");
        } catch (Exception ignored) {
            // Fail one malformed legacy row closed without failing an otherwise valid page.
            return null;
        }
    }

    /**
     * Listing an already-published, owned artifact is not a second publish operation. Keep the
     * presentation/security boundary strict, but tolerate historical state-schema keywords such
     * as enum/minimum/maximum. The current publish path still applies the complete validator.
     */
    private static boolean isSafeLibraryManifest(InteractiveArtifactManifest manifest) {
        return manifest != null
                && manifest.schemaVersion() == 1
                && hasBoundedText(manifest.title(), 1, 80)
                && hasBoundedText(manifest.fallback(), 1, 500)
                && manifest.permissions().isEmpty()
                && manifest.network().isEmpty()
                && "object".equals(manifest.stateSchema().get("type"));
    }

    private static boolean hasBoundedText(String value, int minimum, int maximum) {
        return value != null
                && value.strip().length() >= minimum
                && value.length() <= maximum;
    }

    private NormalizedRequest normalize(PersonalAppListRequest raw) {
        if (raw == null) raw = new PersonalAppListRequest(null, null, null, null, null, null, null, null);
        int limit = parseLimit(raw.limit());
        String sort = raw.sort() == null ? "recent" : raw.sort();
        if (!("recent".equals(sort) || "created".equals(sort))) throw badRequest();
        String q = normalizeQuery(raw.q());
        Long agentId = parseAgentId(raw.agentId());
        String sessionId = raw.sessionId() == null ? null : requireSafeId(raw.sessionId());
        Boolean favorite = parseBoolean(raw.favorite());
        Instant createdAfter = parseInstant(raw.createdAfter());
        String cursor = normalizeCursor(raw.cursor());
        return new NormalizedRequest(
                cursor, limit, sort, q, agentId, sessionId, favorite, createdAfter);
    }

    private int parseLimit(String raw) {
        if (raw == null) return DEFAULT_LIMIT;
        if (!raw.matches("[1-9][0-9]*")) throw badRequest();
        try {
            int value = Integer.parseInt(raw);
            if (value > MAX_LIMIT) throw badRequest();
            return value;
        } catch (NumberFormatException e) {
            throw badRequest();
        }
    }

    private String normalizeQuery(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        if (value.codePointCount(0, value.length()) > MAX_QUERY_CODE_POINTS
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw badRequest();
        }
        return value;
    }

    private Long parseAgentId(String raw) {
        if (raw == null) return null;
        if (!raw.matches("[1-9][0-9]*")) throw badRequest();
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw badRequest();
        }
    }

    private Boolean parseBoolean(String raw) {
        if (raw == null) return null;
        if ("true".equals(raw)) return true;
        if ("false".equals(raw)) return false;
        throw badRequest();
    }

    private Instant parseInstant(String raw) {
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw badRequest();
        }
    }

    private String normalizeCursor(String raw) {
        if (raw == null) return null;
        if (raw.isBlank() || raw.length() > 4_096) throw badRequest();
        return raw;
    }

    private String filterBinding(NormalizedRequest request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(new FilterBinding(
                    request.sort(),
                    request.q(),
                    request.agentId(),
                    request.sessionId(),
                    request.favorite(),
                    request.createdAfter() == null ? null : request.createdAfter().toString()));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to bind personal app filters", e);
        }
    }

    private static void requireUserAndArtifact(long userId, String artifactId) {
        if (userId <= 0) throw badRequest();
        requireSafeId(artifactId);
    }

    private static String requireSafeId(String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) throw badRequest();
        return value;
    }

    private static MobilePersonalAppPreferenceResponse toResponse(
            PersonalAppLibraryRepository.PreferenceRow row) {
        return new MobilePersonalAppPreferenceResponse(
                row.artifactId(), row.favorite(), row.lastOpenedAt());
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid personal app request");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Personal app not found");
    }

    private record NormalizedRequest(
            String cursor,
            int limit,
            String sort,
            String q,
            Long agentId,
            String sessionId,
            Boolean favorite,
            Instant createdAfter) { }

    private record FilterBinding(
            String sort,
            String q,
            Long agentId,
            String sessionId,
            Boolean favorite,
            String createdAfter) { }
}
