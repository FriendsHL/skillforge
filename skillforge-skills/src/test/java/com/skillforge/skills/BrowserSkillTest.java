package com.skillforge.skills;

import com.skillforge.core.skill.SkillResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BrowserSkill 集成测试。
 * 真正启动一个 headless Chromium,用 data: URL 验证浏览器生命周期。
 *
 * <p>如果本机没有安装 Playwright 浏览器(常见于纯净 CI),
 * 第一次操作会抛异常,测试会通过 {@link Assumptions#abort} 优雅跳过,
 * 而不是变成失败。手动安装方式:
 * <pre>mvn -pl skillforge-skills exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"</pre>
 */
class BrowserSkillTest {

    private static final String DATA_URL =
            "data:text/html,<html><head><title>SkillForge%20Smoke</title></head>"
                    + "<body><h1>Hello%20SkillForge</h1><p>integration%20test%20marker</p></body></html>";

    @TempDir
    Path tempProfile;

    private BrowserSkill skill;

    @BeforeEach
    void setUp() {
        // 隔离 profile 目录,避免污染真实 ./data/browser-profile
        skill = new BrowserSkill(tempProfile.toString(), 15_000, 60);
    }

    @AfterEach
    void tearDown() {
        if (skill != null) {
            try {
                skill.shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    @Test
    @DisplayName("schema 与基本元数据正确")
    void schema_metadata_isValid() {
        assertTrue(skill.getName().equalsIgnoreCase("Browser"));
        assertNotNull(skill.getDescription());
        assertFalse(skill.isReadOnly());
        assertNotNull(skill.getToolSchema());
        assertNotNull(skill.getToolSchema().getInputSchema());
    }

    @Test
    @DisplayName("缺少 action 参数时返回错误")
    void missingAction_returnsError() {
        SkillResult r = skill.execute(new HashMap<>(), null);
        assertFalse(r.isSuccess(), "expected error result");
        assertNotNull(r.getError(), "error message should be set");
        assertTrue(r.getError().toLowerCase().contains("action"),
                "error should mention 'action', got: " + r.getError());
    }

    @Test
    @DisplayName("未知 action 返回错误")
    void unknownAction_returnsError() {
        SkillResult r = skill.execute(Map.of("action", "no_such_thing"), null);
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());
        assertTrue(r.getError().toLowerCase().contains("unknown action"),
                "error should mention 'Unknown action', got: " + r.getError());
    }

    @Test
    @DisplayName("getContent 在没有 goto 之前返回错误")
    void getContent_withoutGoto_returnsError() {
        SkillResult r = skill.execute(Map.of("action", "getContent"), null);
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());
        assertTrue(r.getError().toLowerCase().contains("no page open"),
                "should require goto first, got: " + r.getError());
    }

    @Test
    @DisplayName("goto + getContent + close 全链路")
    void goto_then_getContent_then_close() {
        // ---- goto ----
        SkillResult gotoResult = executeOrSkip(Map.of(
                "action", "goto",
                "url", DATA_URL,
                "headless", true
        ));
        assertTrue(gotoResult.isSuccess(),
                "goto should succeed, got: " + gotoResult.getOutput());
        String gotoOutput = gotoResult.getOutput();
        assertTrue(gotoOutput.contains("Hello SkillForge"),
                "goto output should contain page body, got: " + gotoOutput);
        assertTrue(gotoOutput.contains("integration test marker"),
                "goto output should contain marker text, got: " + gotoOutput);

        // ---- getContent (复用同一 page) ----
        SkillResult contentResult = skill.execute(Map.of("action", "getContent"), null);
        assertTrue(contentResult.isSuccess(),
                "getContent should succeed, got: " + contentResult.getOutput());
        assertTrue(contentResult.getOutput().contains("Hello SkillForge"));

        // ---- close ----
        SkillResult closeResult = skill.execute(Map.of("action", "close"), null);
        assertTrue(closeResult.isSuccess(),
                "close should succeed, got: " + closeResult.getOutput());

        // close 之后再 getContent 应该报"no page open"
        SkillResult afterClose = skill.execute(Map.of("action", "getContent"), null);
        assertFalse(afterClose.isSuccess(),
                "getContent after close should fail");
    }

    /**
     * 执行一次 BrowserSkill 操作。如果失败信息暗示是 Playwright 驱动本身没装好,
     * 通过 {@link Assumptions#abort} 跳过本测试,避免在没有 chromium 的环境里红测试。
     */
    private SkillResult executeOrSkip(Map<String, Object> input) {
        SkillResult r = skill.execute(input, null);
        if (!r.isSuccess()) {
            String msg = r.getError() == null ? "" : r.getError().toLowerCase();
            boolean driverIssue = msg.contains("failed to create driver")
                    || msg.contains("executable doesn't exist")
                    || msg.contains("playwright")
                    || msg.contains("chromium");
            if (driverIssue) {
                Assumptions.abort("Playwright driver/browser unavailable, skipping: " + r.getError());
            }
        }
        return r;
    }
}
