package com.skillforge.server.mobile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

@Component
public class MobileAuthInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = "skillforge.mobile.principal";

    private final MobileDeviceService deviceService;

    public MobileAuthInterceptor(MobileDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.sendError(401, "Missing or invalid mobile Authorization header");
            return false;
        }
        String token = authHeader.substring(7);
        Optional<MobileDevicePrincipal> principal = deviceService.authenticate(token);
        if (principal.isEmpty()) {
            response.sendError(401, "Invalid mobile device token");
            return false;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        return true;
    }
}
