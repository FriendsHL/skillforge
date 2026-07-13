package com.skillforge.server.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("MobilePairingController")
class MobilePairingControllerTest {

    private MobilePairingService pairingService;
    private MobileDeviceService deviceService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        pairingService = mock(MobilePairingService.class);
        deviceService = mock(MobileDeviceService.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new MobilePairingController(pairingService, deviceService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /api/mobile/pairings returns QR payload and setup code")
    void createPairing_returnsQrPayloadAndSetupCode() throws Exception {
        UUID pairingId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-07-09T06:05:00Z");
        when(pairingService.createPairing(eq(1L), eq("SkillForge Dev"), eq(List.of("http://192.168.1.10:8080"))))
                .thenReturn(new MobilePairingCreateResponse(
                        pairingId,
                        "pending",
                        "842193",
                        expiresAt,
                        new MobilePairingQrPayload(
                                "skillforge.mobile_pairing",
                                1,
                                "SkillForge Dev",
                                pairingId,
                                "pairing-secret",
                                List.of("http://192.168.1.10:8080"),
                                expiresAt)));

        mvc.perform(post("/api/mobile/pairings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serverName": "SkillForge Dev",
                                  "endpoints": ["http://192.168.1.10:8080"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairingId").value(pairingId.toString()))
                .andExpect(jsonPath("$.setupCode").value("842193"))
                .andExpect(jsonPath("$.qrPayload.type").value("skillforge.mobile_pairing"))
                .andExpect(jsonPath("$.qrPayload.pairingSecret").value("pairing-secret"))
                .andExpect(jsonPath("$.qrPayload.endpoints[0]").value("http://192.168.1.10:8080"));
    }

    @Test
    @DisplayName("POST /api/mobile/pairings/{id}/claim returns one-time device token")
    void claimPairing_returnsDeviceToken() throws Exception {
        UUID pairingId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        when(pairingService.claimPairing(
                eq(pairingId),
                eq(new MobilePairingClaimRequest("pairing-secret", "Youren iPhone", "ios", "1.0.0"))))
                .thenReturn(new MobilePairingClaimResponse(
                        deviceId,
                        "device-token",
                        "SkillForge Dev",
                        new MobileUserResponse(1L),
                        new MobileAgentResponse(3L, "Main Assistant"),
                        new MobileFeatureFlags(true, true, false, false)));

        mvc.perform(post("/api/mobile/pairings/{pairingId}/claim", pairingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pairingSecret": "pairing-secret",
                                  "deviceName": "Youren iPhone",
                                  "platform": "ios",
                                  "appVersion": "1.0.0"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.deviceToken").value("device-token"))
                .andExpect(jsonPath("$.user.id").value(1))
                .andExpect(jsonPath("$.defaultAgent.name").value("Main Assistant"))
                .andExpect(jsonPath("$.features.chat").value(true))
                .andExpect(jsonPath("$.features.attachments").value(true));
    }

    @Test
    @DisplayName("GET /api/mobile/devices returns current user's devices")
    void listDevices_returnsCurrentUserDevices() throws Exception {
        UUID deviceId = UUID.randomUUID();
        when(deviceService.listDevices(1L)).thenReturn(List.of(new MobileDeviceResponse(
                deviceId,
                "Youren iPhone",
                "ios",
                "1.0.0",
                "active",
                Set.of("chat:read"),
                Instant.parse("2026-07-09T06:01:00Z"),
                Instant.parse("2026-07-09T06:00:00Z"),
                null)));

        mvc.perform(get("/api/mobile/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(deviceId.toString()))
                .andExpect(jsonPath("$[0].deviceName").value("Youren iPhone"))
                .andExpect(jsonPath("$[0].status").value("active"));
    }
}
