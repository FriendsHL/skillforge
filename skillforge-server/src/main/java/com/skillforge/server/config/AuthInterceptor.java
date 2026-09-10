package com.skillforge.server.config;

import com.skillforge.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = "skillforge.platform.principal";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.sendError(401, "Missing or invalid Authorization header");
            return false;
        }
        String token = authHeader.substring(7);
        if (!authService.isValidToken(token)) {
            response.sendError(401, "Invalid token");
            return false;
        }
        // The desktop token is a platform-wide capability, not a user identity. Expose that
        // authority explicitly so mutation endpoints never manufacture an actor from user input.
        request.setAttribute(PRINCIPAL_ATTRIBUTE, PlatformAccessPrincipal.platformAdmin());
        return true;
    }
}
