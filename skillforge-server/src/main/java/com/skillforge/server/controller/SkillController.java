package com.skillforge.server.controller;

import com.skillforge.server.entity.SkillEntity;
import com.skillforge.server.repository.SkillRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRepository skillRepository;

    public SkillController(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ResponseEntity<List<SkillEntity>> listSkills() {
        List<SkillEntity> skills = skillRepository.findAll();
        return ResponseEntity.ok(skills);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillEntity> getSkill(@PathVariable Long id) {
        SkillEntity skill = skillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found: " + id));
        return ResponseEntity.ok(skill);
    }

    @PostMapping("/upload")
    public ResponseEntity<SkillEntity> uploadSkill(@RequestParam("file") MultipartFile file) {
        // TODO: 解压 zip、解析 skill.yaml + SKILL.md、存储、注册到 SkillRegistry
        // 目前先创建一个占位 entity
        SkillEntity skill = new SkillEntity();
        skill.setName(file.getOriginalFilename());
        skill.setDescription("Uploaded skill package");
        SkillEntity saved = skillRepository.save(skill);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable Long id) {
        skillRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
