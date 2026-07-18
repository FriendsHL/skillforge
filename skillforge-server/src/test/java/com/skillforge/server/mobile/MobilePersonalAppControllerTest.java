package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MobilePersonalAppControllerTest {

    private static final Instant CREATED = Instant.parse("2026-07-17T02:00:00Z");
    private static final Instant OPENED = Instant.parse("2026-07-17T03:00:00Z");

    private PersonalAppLibraryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PersonalAppLibraryService.class);
        mvc = mvc(new MobilePersonalAppController(service, objectMapper()));
    }

    @Test
    void listReturnsExactSafeEnvelopeAndNoStore() throws Exception {
        when(service.list(eq(7L), eq("token-7"), any())).thenReturn(
                new MobilePersonalAppListResponse(List.of(new MobilePersonalAppItemResponse(
                        "artifact-1", "session-1", 12L, "Daily Brief", "Summary", 1,
                        List.of(), List.of(), 9L, "Main Assistant", "Research",
                        CREATED, OPENED, true, "available")), "next-signed-cursor"));

        mvc.perform(get("/api/mobile/client/personal-apps")
                        .header("Authorization", "Bearer token-7")
                        .param("limit", "20")
                        .param("sort", "recent")
                        .with(principal("chat:read")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].artifactId").value("artifact-1"))
                .andExpect(jsonPath("$.items[0].sourceMessageSeq").value(12))
                .andExpect(jsonPath("$.items[0].schemaVersion").value(1))
                .andExpect(jsonPath("$.items[0].availability").value("available"))
                .andExpect(jsonPath("$.nextCursor").value("next-signed-cursor"))
                .andExpect(jsonPath("$.items[0].manifestJson").doesNotExist())
                .andExpect(jsonPath("$.items[0].initialData").doesNotExist())
                .andExpect(jsonPath("$.items[0].stateSchema").doesNotExist())
                .andExpect(jsonPath("$.items[0].storagePath").doesNotExist())
                .andExpect(jsonPath("$.items[0].sha256").doesNotExist());
    }

    @Test
    void listRequiresPrincipalScopeBearerAndRejectsParameterPollution() throws Exception {
        mvc.perform(get("/api/mobile/client/personal-apps")
                        .header("Authorization", "Bearer token-7"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mobile/client/personal-apps")
                        .header("Authorization", "Bearer token-7")
                        .with(principal("chat:write")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mobile/client/personal-apps")
                        .with(principal("chat:read")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mobile/client/personal-apps")
                        .header("Authorization", "Bearer token-7")
                        .param("sort", "recent", "created")
                        .with(principal("chat:read")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/mobile/client/personal-apps")
                        .header("Authorization", "Bearer token-7")
                        .param("unknown", "value")
                        .with(principal("chat:read")))
                .andExpect(status().isBadRequest());
        verify(service, never()).list(anyLong(), any(), any());
    }

    @Test
    void cursorFilterMismatchIsControlledBadRequest() throws Exception {
        when(service.list(eq(7L), eq("token-7"), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST));

        mvc.perform(get("/api/mobile/client/personal-apps")
                        .header("Authorization", "Bearer token-7")
                        .param("cursor", "signed-for-other-filter")
                        .param("q", "changed")
                        .with(principal("chat:read")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchPreferenceRequiresExactlyOneBooleanField() throws Exception {
        when(service.setFavorite(7L, "artifact-1", true)).thenReturn(
                new MobilePersonalAppPreferenceResponse("artifact-1", true, OPENED));

        mvc.perform(patch("/api/mobile/client/personal-apps/artifact-1/preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":true}")
                        .with(principal("chat:read")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.artifactId").value("artifact-1"))
                .andExpect(jsonPath("$.favorite").value(true))
                .andExpect(jsonPath("$.lastOpenedAt").value("2026-07-17T03:00:00Z"));

        for (String body : List.of(
                "{}",
                "{\"favorite\":\"true\"}",
                "{\"favorite\":true,\"favorite\":false}",
                "{\"favorite\":true,\"extra\":1}",
                "[]")) {
            mvc.perform(patch("/api/mobile/client/personal-apps/artifact-1/preference")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(principal("chat:read")))
                    .andExpect(status().isBadRequest());
        }
        verify(service).setFavorite(7L, "artifact-1", true);
    }

    @Test
    void inaccessibleAndMissingMutationsUseSameNotFoundStatus() throws Exception {
        when(service.setFavorite(7L, "hidden", false))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(service.markOpened(7L, "hidden"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(patch("/api/mobile/client/personal-apps/hidden/preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favorite\":false}")
                        .with(principal("chat:read")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/mobile/client/personal-apps/hidden/opened")
                        .with(principal("chat:read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokedDeviceIsRejectedBeforeControllerAndService() throws Exception {
        MobileDeviceService deviceService = mock(MobileDeviceService.class);
        when(deviceService.authenticate("revoked-token")).thenReturn(Optional.empty());
        MockMvc authenticatedMvc = MockMvcBuilders
                .standaloneSetup(new MobilePersonalAppController(service, objectMapper()))
                .addInterceptors(new MobileAuthInterceptor(deviceService))
                .setMessageConverters(converter())
                .build();

        authenticatedMvc.perform(get("/api/mobile/client/personal-apps")
                        .header("Authorization", "Bearer revoked-token"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).list(anyLong(), any(), any());
    }

    private static MockMvc mvc(MobilePersonalAppController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter())
                .build();
    }

    private static MappingJackson2HttpMessageConverter converter() {
        return new MappingJackson2HttpMessageConverter(objectMapper());
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static RequestPostProcessor principal(String... scopes) {
        return request -> {
            request.setAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE,
                    new MobileDevicePrincipal(
                            UUID.fromString("00000000-0000-0000-0000-000000000007"),
                            7L,
                            "iPhone",
                            Set.of(scopes)));
            return request;
        };
    }
}
