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

class GrepSkillTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("workingDirectory 为 null 且无 path 参数时不抛 NPE，回退到 JVM 工作目录")
    void execute_nullWorkingDirectory_fallsBackToSystemUserDir() {
        GrepSkill skill = new GrepSkill();
        SkillContext context = new SkillContext(null, "s-null-wd", 1L);

        SkillResult result = skill.execute(Map.of("pattern", "nonexistent_xyz_12345"), context);

        assertTrue(result.isSuccess(), "应成功而非 NPE：" + result.getError());
    }

    @Test
    @DisplayName("缺少 pattern 时返回错误")
    void execute_missingPattern_returnsError() {
        GrepSkill skill = new GrepSkill();

        SkillResult result = skill.execute(Map.of(), new SkillContext());

        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("在给定目录中找到匹配内容")
    void execute_findsMatchInFile() throws Exception {
        GrepSkill skill = new GrepSkill();
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "hello world\nfoo bar\n");
        SkillContext context = new SkillContext(tempDir.toString(), "s1", 1L);

        SkillResult result = skill.execute(
                Map.of("pattern", "hello", "path", tempDir.toString()),
                context);

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("hello"));
    }
}
