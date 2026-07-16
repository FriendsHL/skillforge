package com.skillforge.server.mobile;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/mobile/client")
public class MobileClientController {

    private static final String SCOPE_AGENT_READ = "agent:read";

    private final MobileAgentAccessService mobileAgentAccessService;

    public MobileClientController(MobileAgentAccessService mobileAgentAccessService) {
        this.mobileAgentAccessService = mobileAgentAccessService;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest request) {
        Object principal = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof MobileDevicePrincipal mobilePrincipal)) {
            return ResponseEntity.status(401).build();
        }
        MobileAgentResponse defaultAgent = mobileAgentAccessService.findSelectableDefaultAgent(mobilePrincipal.userId())
                .map(agent -> new MobileAgentResponse(agent.getId(), agent.getName()))
                .orElse(new MobileAgentResponse(null, MobileAgentAccessService.DEFAULT_AGENT_NAME));
        return ResponseEntity.ok(Map.of(
                "user", new MobileUserResponse(mobilePrincipal.userId()),
                "device", Map.of(
                        "id", mobilePrincipal.deviceId(),
                        "deviceName", mobilePrincipal.deviceName(),
                        "scopes", mobilePrincipal.scopes()),
                "defaultAgent", defaultAgent,
                "features", new MobileFeatureFlags(true, true, true, true)));
    }

    @GetMapping("/agents")
    public ResponseEntity<List<MobileAgentListItemResponse>> listAgents(HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_AGENT_READ);
        return ResponseEntity.ok(mobileAgentAccessService.listSelectableAgents(principal.userId()));
    }

    @GetMapping("/agents/{agentId}")
    public ResponseEntity<MobileAgentDetailResponse> getAgent(
            @PathVariable Long agentId,
            HttpServletRequest request) {
        MobileDevicePrincipal principal = requirePrincipal(request);
        requireScope(principal, SCOPE_AGENT_READ);
        return ResponseEntity.ok(mobileAgentAccessService.getAgentDetail(agentId, principal.userId()));
    }

    private MobileDevicePrincipal requirePrincipal(HttpServletRequest request) {
        Object principal = request.getAttribute(MobileAuthInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof MobileDevicePrincipal mobilePrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return mobilePrincipal;
    }

    private void requireScope(MobileDevicePrincipal principal, String scope) {
        Set<String> scopes = principal.scopes() != null ? principal.scopes() : Set.of();
        if (!scopes.contains(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
