package com.skillforge.server.mobile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Set;

@RestController
@RequestMapping("/api/mobile/client/personal-apps")
public class MobilePersonalAppController {

    private static final String SCOPE_CHAT_READ = "chat:read";
    private static final int MAX_PREFERENCE_BODY_BYTES = 128;
    private static final Set<String> LIST_PARAMETERS = Set.of(
            "cursor", "limit", "sort", "q", "agentId", "sessionId", "favorite", "createdAfter");

    private final PersonalAppLibraryService service;
    private final ObjectMapper objectMapper;

    public MobilePersonalAppController(
            PersonalAppLibraryService service,
            ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MobilePersonalAppListResponse> list(
            @RequestParam MultiValueMap<String, String> parameters,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipalAndScope(request);
        validateParameters(parameters);
        PersonalAppListRequest listRequest = new PersonalAppListRequest(
                single(parameters, "cursor"),
                single(parameters, "limit"),
                single(parameters, "sort"),
                single(parameters, "q"),
                single(parameters, "agentId"),
                single(parameters, "sessionId"),
                single(parameters, "favorite"),
                single(parameters, "createdAfter"));
        return noStore(service.list(
                principal.userId(), requireBearerToken(request), listRequest));
    }

    @PatchMapping(
            value = "/{artifactId}/preference",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MobilePersonalAppPreferenceResponse> setPreference(
            @PathVariable String artifactId,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipalAndScope(request);
        JsonNode body = readPreferenceBody(request);
        if (body == null || !body.isObject() || body.size() != 1
                || !body.has("favorite") || !body.path("favorite").isBoolean()) {
            throw badRequest();
        }
        return noStore(service.setFavorite(
                principal.userId(), artifactId, body.path("favorite").booleanValue()));
    }

    @PostMapping(value = "/{artifactId}/opened", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MobilePersonalAppPreferenceResponse> markOpened(
            @PathVariable String artifactId,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipalAndScope(request);
        return noStore(service.markOpened(principal.userId(), artifactId));
    }

    private JsonNode readPreferenceBody(HttpServletRequest request) {
        try {
            byte[] json = request.getInputStream().readNBytes(MAX_PREFERENCE_BODY_BYTES + 1);
            if (json.length == 0 || json.length > MAX_PREFERENCE_BODY_BYTES) throw badRequest();
            try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
                parser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
                JsonNode body = objectMapper.readTree(parser);
                if (parser.nextToken() != null) throw badRequest();
                return body;
            }
        } catch (IOException e) {
            throw badRequest();
        }
    }

    private static MobileDevicePrincipal requirePrincipalAndScope(HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof MobileDevicePrincipal principal)
                || principal.userId() == null || principal.userId() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Set<String> scopes = principal.scopes() == null ? Set.of() : principal.scopes();
        if (!scopes.contains(SCOPE_CHAT_READ)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    private static String requireBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        String token = authorization.substring(7);
        if (token.isBlank() || token.length() > 4_096
                || token.codePoints().anyMatch(Character::isWhitespace)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return token;
    }

    private static void validateParameters(MultiValueMap<String, String> parameters) {
        if (!LIST_PARAMETERS.containsAll(parameters.keySet())) throw badRequest();
        for (var entry : parameters.entrySet()) {
            if (entry.getValue() == null || entry.getValue().size() != 1) throw badRequest();
        }
    }

    private static String single(MultiValueMap<String, String> parameters, String name) {
        var values = parameters.get(name);
        return values == null ? null : values.get(0);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid personal app request");
    }
}
