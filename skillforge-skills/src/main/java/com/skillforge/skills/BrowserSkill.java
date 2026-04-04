package com.skillforge.skills;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill that automates browser interactions using Playwright.
 * Supports navigation, content extraction, screenshots, clicking, typing,
 * and JavaScript evaluation.
 */
public class BrowserSkill implements Skill {

    private static final int DEFAULT_TIMEOUT_MS = 30000;
    private static final int MAX_GOTO_CONTENT_LENGTH = 5000;
    private static final int MAX_CONTENT_LENGTH = 10000;

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private boolean currentHeadless = true;

    @Override
    public String getName() {
        return "Browser";
    }

    @Override
    public String getDescription() {
        return "Automates browser interactions using Playwright. Supports navigating to URLs, "
                + "extracting page content, taking screenshots, clicking elements, typing text, "
                + "and evaluating JavaScript.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "Action to perform: goto, getContent, screenshot, click, type, evaluate, close",
                "enum", List.of("goto", "getContent", "screenshot", "click", "type", "evaluate", "close")
        ));
        properties.put("url", Map.of(
                "type", "string",
                "description", "URL to navigate to (for goto action)"
        ));
        properties.put("selector", Map.of(
                "type", "string",
                "description", "CSS selector (for click/type actions)"
        ));
        properties.put("text", Map.of(
                "type", "string",
                "description", "Text to type (for type action)"
        ));
        properties.put("script", Map.of(
                "type", "string",
                "description", "JavaScript to evaluate (for evaluate action)"
        ));
        properties.put("headless", Map.of(
                "type", "boolean",
                "description", "Run browser in headless mode (default: true)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String action = (String) input.get("action");
            if (action == null || action.isBlank()) {
                return SkillResult.error("action is required");
            }

            return switch (action) {
                case "goto" -> handleGoto(input);
                case "getContent" -> handleGetContent();
                case "screenshot" -> handleScreenshot();
                case "click" -> handleClick(input);
                case "type" -> handleType(input);
                case "evaluate" -> handleEvaluate(input);
                case "close" -> handleClose();
                default -> SkillResult.error("Unknown action: " + action
                        + ". Supported: goto, getContent, screenshot, click, type, evaluate, close");
            };
        } catch (Exception e) {
            return SkillResult.error("Browser error: " + e.getMessage());
        }
    }

    private void ensureBrowser(boolean headless) {
        if (browser != null && currentHeadless != headless) {
            // headless mode changed, restart browser
            closeBrowserQuietly();
        }
        if (browser == null || !browser.isConnected()) {
            if (playwright == null) {
                playwright = Playwright.create();
            }
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions();
            options.setHeadless(headless);
            browser = playwright.chromium().launch(options);
            currentHeadless = headless;
            page = null;
        }
        if (page == null || page.isClosed()) {
            page = browser.newPage();
            page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);
        }
    }

    private SkillResult handleGoto(Map<String, Object> input) {
        String url = (String) input.get("url");
        if (url == null || url.isBlank()) {
            return SkillResult.error("url is required for goto action");
        }

        boolean headless = true;
        if (input.containsKey("headless") && input.get("headless") != null) {
            headless = Boolean.TRUE.equals(input.get("headless"));
        }

        ensureBrowser(headless);
        page.navigate(url);

        String title = page.title();
        String textContent = page.innerText("body");
        if (textContent.length() > MAX_GOTO_CONTENT_LENGTH) {
            textContent = textContent.substring(0, MAX_GOTO_CONTENT_LENGTH)
                    + "\n... [content truncated at " + MAX_GOTO_CONTENT_LENGTH + " characters]";
        }

        return SkillResult.success("Title: " + title + "\n\n" + textContent);
    }

    private SkillResult handleGetContent() {
        if (page == null || page.isClosed()) {
            return SkillResult.error("No page open. Use goto action first.");
        }

        String title = page.title();
        String textContent = page.innerText("body");
        if (textContent.length() > MAX_CONTENT_LENGTH) {
            textContent = textContent.substring(0, MAX_CONTENT_LENGTH)
                    + "\n... [content truncated at " + MAX_CONTENT_LENGTH + " characters]";
        }

        return SkillResult.success("Title: " + title + "\n\n" + textContent);
    }

    private SkillResult handleScreenshot() {
        if (page == null || page.isClosed()) {
            return SkillResult.error("No page open. Use goto action first.");
        }

        try {
            Path tempFile = Files.createTempFile("browser-screenshot-", ".png");
            page.screenshot(new Page.ScreenshotOptions().setPath(tempFile).setFullPage(true));
            return SkillResult.success("Screenshot saved to: " + tempFile.toAbsolutePath());
        } catch (Exception e) {
            return SkillResult.error("Failed to take screenshot: " + e.getMessage());
        }
    }

    private SkillResult handleClick(Map<String, Object> input) {
        if (page == null || page.isClosed()) {
            return SkillResult.error("No page open. Use goto action first.");
        }

        String selector = (String) input.get("selector");
        if (selector == null || selector.isBlank()) {
            return SkillResult.error("selector is required for click action");
        }

        page.click(selector);
        return SkillResult.success("Clicked element: " + selector);
    }

    private SkillResult handleType(Map<String, Object> input) {
        if (page == null || page.isClosed()) {
            return SkillResult.error("No page open. Use goto action first.");
        }

        String selector = (String) input.get("selector");
        if (selector == null || selector.isBlank()) {
            return SkillResult.error("selector is required for type action");
        }

        String text = (String) input.get("text");
        if (text == null) {
            return SkillResult.error("text is required for type action");
        }

        page.fill(selector, text);
        return SkillResult.success("Typed text into element: " + selector);
    }

    private SkillResult handleEvaluate(Map<String, Object> input) {
        if (page == null || page.isClosed()) {
            return SkillResult.error("No page open. Use goto action first.");
        }

        String script = (String) input.get("script");
        if (script == null || script.isBlank()) {
            return SkillResult.error("script is required for evaluate action");
        }

        Object result = page.evaluate(script);
        String resultStr = result != null ? result.toString() : "null";
        return SkillResult.success("Result: " + resultStr);
    }

    private SkillResult handleClose() {
        closeBrowserQuietly();
        return SkillResult.success("Browser closed.");
    }

    private void closeBrowserQuietly() {
        try {
            if (page != null && !page.isClosed()) {
                page.close();
            }
        } catch (Exception ignored) {
        }
        page = null;

        try {
            if (browser != null && browser.isConnected()) {
                browser.close();
            }
        } catch (Exception ignored) {
        }
        browser = null;

        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
        playwright = null;
    }
}
