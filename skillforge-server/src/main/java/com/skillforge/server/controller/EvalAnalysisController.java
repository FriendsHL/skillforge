package com.skillforge.server.controller;

import com.skillforge.server.dto.EvalAnalysisCreateRequest;
import com.skillforge.server.dto.EvalAnalysisSessionResponse;
import com.skillforge.server.entity.EvalAnalysisSessionEntity;
import com.skillforge.server.entity.SessionEntity;
import com.skillforge.server.service.EvalAnalysisSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/eval/scenarios")
public class EvalAnalysisController {

    private final EvalAnalysisSessionService evalAnalysisSessionService;

    public EvalAnalysisController(EvalAnalysisSessionService evalAnalysisSessionService) {
        this.evalAnalysisSessionService = evalAnalysisSessionService;
    }

    @PostMapping("/{id}/analyze")
    public ResponseEntity<?> analyzeScenario(@PathVariable("id") String scenarioId,
                                             @RequestBody EvalAnalysisCreateRequest request) {
        Long userId = request.userId();
        Long agentId = request.agentId();
        if (scenarioId == null || scenarioId.isBlank() || userId == null || agentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "scenarioId, userId and agentId are required"));
        }
        SessionEntity session = evalAnalysisSessionService
                .createScenarioHistoryAnalysisSession(userId, agentId, scenarioId);
        return ResponseEntity.ok(new EvalAnalysisSessionResponse(
                session.getId(),
                EvalAnalysisSessionEntity.TYPE_SCENARIO_HISTORY,
                scenarioId,
                null,
                null
        ));
    }

    @GetMapping("/{id}/analysis-sessions")
    public ResponseEntity<List<Map<String, Object>>> listScenarioAnalysisSessions(@PathVariable("id") String scenarioId,
                                                                                  @RequestParam("userId") Long userId) {
        if (scenarioId == null || scenarioId.isBlank() || userId == null) {
            return ResponseEntity.badRequest().body(List.of());
        }
        List<Map<String, Object>> result = evalAnalysisSessionService.listScenarioAnalysisSessions(scenarioId, userId)
                .stream()
                .map(this::toSessionMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toSessionMap(SessionEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("agentId", s.getAgentId());
        m.put("title", s.getTitle());
        m.put("status", s.getStatus());
        m.put("runtimeStatus", s.getRuntimeStatus());
        m.put("messageCount", s.getMessageCount());
        m.put("createdAt", s.getCreatedAt());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }
}
