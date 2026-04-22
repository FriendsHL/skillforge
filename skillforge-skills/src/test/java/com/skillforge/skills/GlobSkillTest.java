package com.skillforge.skills;

import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobSkillTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("缺少 pattern 参数时返回错误")
    void execute_missingPattern_returnsError() {
        GlobSkill skill = new GlobSkill();

        SkillResult result = skill.execute(Map.of(), new SkillContext());

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("pattern is required"));
    }

    @Test
    @DisplayName("空白 pattern 参数时返回错误")
    void execute_blankPattern_returnsError() {
        GlobSkill skill = new GlobSkill();

        SkillResult result = skill.execute(
                Map.of("pattern", "   "),
                new SkillContext(tempDir.toString(), "s-blank", 1L));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("pattern is required"));
    }

    @Test
    @DisplayName("未提供 path 时使用 SkillContext 的工作目录")
    void execute_withoutPath_usesContextWorkingDirectory() throws Exception {
        GlobSkill skill = new GlobSkill();
        Path txt = tempDir.resolve("hello.txt");
        Files.writeString(txt, "hello");
        SkillContext context = new SkillContext(tempDir.toString(), "s1", 1L);

        SkillResult result = skill.execute(Map.of("pattern", "*.txt"), context);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(txt.toString()));
    }

    @Test
    @DisplayName("path 不是目录时返回错误")
    void execute_pathIsFile_returnsError() throws Exception {
        GlobSkill skill = new GlobSkill();
        Path filePath = tempDir.resolve("not-dir.txt");
        Files.writeString(filePath, "x");

        SkillResult result = skill.execute(
                Map.of("pattern", "*.txt", "path", filePath.toString()),
                new SkillContext(tempDir.toString(), "s2", 1L));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Path is not a directory"));
    }

    @Test
    @DisplayName("无匹配文件时返回成功并提示")
    void execute_noMatches_returnsSuccessMessage() {
        GlobSkill skill = new GlobSkill();

        SkillResult result = skill.execute(
                Map.of("pattern", "*.json", "path", tempDir.toString()),
                new SkillContext(tempDir.toString(), "s-no-match", 1L));

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No files matched pattern"));
    }

    @Test
    @DisplayName("workingDirectory 为 null 且无 path 参数时不抛 NPE，回退到 JVM 工作目录")
    void execute_nullWorkingDirectory_fallsBackToSystemUserDir() {
        GlobSkill skill = new GlobSkill();
        SkillContext context = new SkillContext(null, "s-null-wd", 1L);

        SkillResult result = skill.execute(Map.of("pattern", "*.nonexistent_xyz"), context);

        assertTrue(result.isSuccess(), "应成功而非 NPE：" + result.getError());
    }
}
