package com.skillforge.server.mobile;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/mobile/client/push-token")
public class MobilePushController {
    private final MobilePushTokenService tokenService;
    public MobilePushController(MobilePushTokenService tokenService) { this.tokenService = tokenService; }

    @PostMapping
    public ResponseEntity<MobilePushTokenResponse> register(@RequestBody MobilePushTokenRequest body,
                                                            HttpServletRequest request) {
        try {
            return ResponseEntity.ok(tokenService.register(principal(request), body.token(), body.environment()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(@RequestParam String environment, HttpServletRequest request) {
        tokenService.unregister(principal(request), environment);
        return ResponseEntity.noContent().build();
    }

    private MobileDevicePrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (value instanceof MobileDevicePrincipal principal) return principal;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
