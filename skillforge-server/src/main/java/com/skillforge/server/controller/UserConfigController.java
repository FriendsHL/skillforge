package com.skillforge.server.controller;

import com.skillforge.server.service.UserConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/user-config")
public class UserConfigController {

    private final UserConfigService userConfigService;

    public UserConfigController(UserConfigService userConfigService) {
        this.userConfigService = userConfigService;
    }

    @GetMapping("/claude-md")
    public ResponseEntity<Map<String, String>> getClaudeMd(@RequestParam Long userId) {
        String content = userConfigService.getClaudeMd(userId);
        return ResponseEntity.ok(Map.of("claudeMd", content != null ? content : ""));
    }

    @PutMapping("/claude-md")
    public ResponseEntity<Map<String, String>> saveClaudeMd(@RequestParam Long userId,
                                                              @RequestBody Map<String, String> body) {
        userConfigService.saveClaudeMd(userId, body.get("claudeMd"));
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
