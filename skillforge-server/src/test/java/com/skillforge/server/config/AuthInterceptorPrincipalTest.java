package com.skillforge.server.config;

import com.skillforge.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorPrincipalTest {

    @Test
    void validSharedTokenCreatesExplicitPlatformAuthority() throws Exception {
        AuthService authService = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(authService.isValidToken("valid-token")).thenReturn(true);

        boolean accepted = new AuthInterceptor(authService).preHandle(
                request, response, new Object());

        assertThat(accepted).isTrue();
        assertThat(PlatformAccessPrincipal.platformAdmin().permissions())
                .containsExactly(
                        PlatformAccessPrincipal.SESSION_RESOLVE_UNKNOWN_PERMISSION);
        verify(request).setAttribute(
                AuthInterceptor.PRINCIPAL_ATTRIBUTE,
                PlatformAccessPrincipal.platformAdmin());
        verify(response, never()).sendError(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void invalidTokenNeverCreatesAuthority() throws Exception {
        AuthService authService = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");

        boolean accepted = new AuthInterceptor(authService).preHandle(
                request, response, new Object());

        assertThat(accepted).isFalse();
        verify(request, never()).setAttribute(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(response).sendError(401, "Invalid token");
    }
}
