package com.skillforge.server.mobile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MobileAuthInterceptor")
class MobileAuthInterceptorTest {

    @Test
    @DisplayName("preHandle stores mobile principal for a valid device token")
    void preHandle_storesPrincipalForValidToken() throws Exception {
        MobileDeviceService deviceService = mock(MobileDeviceService.class);
        MobileDevicePrincipal principal = new MobileDevicePrincipal(
                UUID.randomUUID(), 1L, "Youren iPhone", Set.of("chat:read"));
        when(deviceService.authenticate("device-token")).thenReturn(Optional.of(principal));
        MobileAuthInterceptor interceptor = new MobileAuthInterceptor(deviceService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer device-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE)).isEqualTo(principal);
    }

    @Test
    @DisplayName("preHandle rejects missing or invalid device token")
    void preHandle_rejectsMissingOrInvalidToken() throws Exception {
        MobileDeviceService deviceService = mock(MobileDeviceService.class);
        when(deviceService.authenticate("bad-token")).thenReturn(Optional.empty());
        MobileAuthInterceptor interceptor = new MobileAuthInterceptor(deviceService);

        HttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(((MockHttpServletResponse) response).getStatus()).isEqualTo(401);

        MockHttpServletRequest invalidRequest = new MockHttpServletRequest();
        invalidRequest.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(invalidRequest, invalidResponse, new Object())).isFalse();
        assertThat(invalidResponse.getStatus()).isEqualTo(401);
    }
}
