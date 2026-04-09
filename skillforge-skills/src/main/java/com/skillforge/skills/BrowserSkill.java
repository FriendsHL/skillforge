package com.skillforge.skills;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Skill;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Skill that automates browser interactions using Playwright.
 * 使用持久化用户数据目录保持登录态跨会话复用。
 * 支持:
 * <ul>
 *   <li>goto / getContent / screenshot / click / type / evaluate / close — 标准浏览器操作</li>
 *   <li>login — headed 模式打开登录页,阻塞等待用户手动登录并关闭窗口,登录态落盘</li>
 * </ul>
 */
public class BrowserSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(BrowserSkill.class);
    private static final int MAX_GOTO_CONTENT_LENGTH = 5000;
    private static final int MAX_CONTENT_LENGTH = 10000;

    private final String profileDir;
    private final int defaultTimeoutMs;
    private final int loginTimeoutSeconds;

    private Playwright playwright;
    private BrowserContext context;
    private Page page;
    private boolean currentHeadless = true;

    public BrowserSkill() {
        this("./data/browser-profile", 30000, 300);
    }

    public BrowserSkill(String profileDir, int defaultTimeoutMs, int loginTimeoutSeconds) {
        this.profileDir = profileDir;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.loginTimeoutSeconds = loginTimeoutSeconds;
    }

    @Override
    public String getName() {
        return "Browser";
    }

    @Override
    public String getDescription() {
        return "Automates browser interactions using Playwright with persistent login state. "
                + "Supports navigating to URLs, extracting page content, taking screenshots, "
                + "clicking elements, typing text, evaluating JavaScript, and interactive login. "
                + "Use the 'login' action to open a visible browser window for the user to manually "
                + "sign in to a site; the session will be persisted for future headless calls.";
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "Action to perform: goto, getContent, screenshot, click, type, evaluate, login, close",
                "enum", List.of("goto", "getContent", "screenshot", "click", "type", "evaluate", "login", "close")
        ));
        properties.put("url", Map.of(
                "type", "string",
                "description", "URL to navigate to (for goto/login actions)"
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
                "description", "Run browser in headless mode (default: true). Ignored by login action (always headed)."
        ));
        properties.put("timeoutSeconds", Map.of(
                "type", "integer",
                "description", "For login action: max seconds to wait for user to finish logging in (default: 300)"
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
                case "login" -> handleLogin(input);
                case "close" -> handleClose();
                default -> SkillResult.error("Unknown action: " + action
                        + ". Supported: goto, getContent, screenshot, click, type, evaluate, login, close");
            };
        } catch (Exception e) {
            log.error("BrowserSkill execution failed", e);
            // Detect the well-known Spring Boot fat-jar + Playwright incompatibility:
            // Playwright's DriverJar.initialize calls ClassLoader.getResource() which
            // returns null inside Spring Boot's nested-jar protocol. Give the LLM and
            // operator a clear, actionable message instead of a raw NPE stack trace.
            String diag = diagnoseFatJarPlaywrightFailure(e);
            if (diag != null) {
                return SkillResult.error(diag);
            }
            return SkillResult.error("Browser error: " + e.getMessage());
        }
    }

    /**
     * Returns a clear actionable error if the throwable looks like Playwright's known
     * "ClassLoader.getResource returned null" failure under Spring Boot's nested classpath.
     * Returns null otherwise so the caller falls back to a generic error.
     */
    private String diagnoseFatJarPlaywrightFailure(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null
                    && msg.contains("ClassLoader.getResource")
                    && msg.contains("toURI")) {
                return "BrowserSkill is unavailable: Playwright cannot locate its native "
                        + "driver bundle when the server runs from a Spring Boot fat jar "
                        + "(java -jar). Workarounds: (1) start the server via "
                        + "`mvn spring-boot:run` instead of `java -jar`, or (2) deploy in a "
                        + "container with Playwright pre-installed and PLAYWRIGHT_BROWSERS_PATH "
                        + "set. See README \"Browser skill / Playwright deployment\" for details.";
            }
            cur = cur.getCause();
        }
        return null;
    }

    /**
     * 确保 BrowserContext 可用,并按需切换 headless 模式。
     * 使用 launchPersistentContext 保持登录态。
     */
    private void ensureContext(boolean headless) {
        if (context != null && currentHeadless != headless) {
            // headless 模式变更,关闭重建
            closeContextQuietly();
        }
        if (context == null) {
            if (playwright == null) {
                playwright = Playwright.create();
            }

            Path userDataDir = Paths.get(profileDir).toAbsolutePath();
            try {
                Files.createDirectories(userDataDir);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create browser profile dir: " + userDataDir, e);
            }

            BrowserType.LaunchPersistentContextOptions options =
                    new BrowserType.LaunchPersistentContextOptions().setHeadless(headless);
            context = playwright.chromium().launchPersistentContext(userDataDir, options);
            currentHeadless = headless;
            page = null;
        }
        if (page == null || page.isClosed()) {
            // 复用 context 自动创建的第一个 page,否则新建一个
            page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            page.setDefaultTimeout(defaultTimeoutMs);
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

        ensureContext(headless);
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

    /**
     * 打开可见浏览器窗口让用户手动登录。
     * 阻塞等待用户关闭浏览器窗口(表示登录完成),或超时。
     * 登录态通过持久化 profile 目录自动保存,后续 goto 调用会自动携带。
     */
    private SkillResult handleLogin(Map<String, Object> input) {
        String url = (String) input.get("url");

        int timeoutSec = loginTimeoutSeconds;
        Object timeoutVal = input.get("timeoutSeconds");
        if (timeoutVal instanceof Number) {
            timeoutSec = ((Number) timeoutVal).intValue();
        }

        // 强制重建 headed context (headless -> headed 需要关闭 profile 锁)
        closeContextQuietly();
        ensureContext(false);

        if (url != null && !url.isBlank()) {
            page.navigate(url);
        }

        // 监听 context close,作为"用户完成登录"的信号
        CountDownLatch latch = new CountDownLatch(1);
        context.onClose(c -> latch.countDown());

        boolean completedInTime;
        try {
            completedInTime = latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeContextQuietly();
            return SkillResult.error("Login wait interrupted");
        }

        if (!completedInTime) {
            closeContextQuietly();
            return SkillResult.error("Login timed out after " + timeoutSec
                    + " seconds. Please call login again.");
        }

        // 用户关闭了窗口,清理内部引用 (context 已被 Playwright 标记关闭)
        context = null;
        page = null;

        return SkillResult.success(
                "Login window closed. Session state has been persisted to profile dir. "
                + "Subsequent goto calls will automatically use the logged-in session.");
    }

    private SkillResult handleClose() {
        closeContextQuietly();
        return SkillResult.success("Browser closed.");
    }

    /**
     * 外部调用,服务关闭时清理资源。
     */
    public void shutdown() {
        closeContextQuietly();
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (Exception ignored) {
        }
        playwright = null;
    }

    private void closeContextQuietly() {
        try {
            if (page != null && !page.isClosed()) {
                page.close();
            }
        } catch (Exception ignored) {
        }
        page = null;

        try {
            if (context != null) {
                context.close();
            }
        } catch (Exception ignored) {
        }
        context = null;
    }
}
