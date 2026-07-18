package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.artifact.InteractiveArtifactValidator;
import com.skillforge.workflow.schema.SchemaValidator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalAppTemplateCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesOnlyValidatedAllowlistedTemplatesWithDefensiveBytes() throws Exception {
        PersonalAppTemplateCatalog catalog = new PersonalAppTemplateCatalog(objectMapper);
        InteractiveArtifactValidator validator = new InteractiveArtifactValidator(objectMapper);

        assertThat(catalog.templateIds()).containsExactlyInAnyOrder(
                "ai-daily-brief-v1", "budget-planner-v1");
        for (String templateId : catalog.templateIds()) {
            PersonalAppTemplateCatalog.Template template = catalog.find(templateId).orElseThrow();
            byte[] sourceBytes;
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
                    "personal-app-templates/v1/" + template.filename())) {
                assertThat(stream).isNotNull();
                sourceBytes = stream.readAllBytes();
            }
            assertThat(template.htmlBytes()).isEqualTo(sourceBytes);
            assertThatCode(() -> validator.validate(template.manifest(), template.htmlBytes()))
                    .doesNotThrowAnyException();

            byte[] callerCopy = template.htmlBytes();
            callerCopy[0] = (byte) (callerCopy[0] + 1);
            assertThat(catalog.find(templateId).orElseThrow().htmlBytes()).isEqualTo(sourceBytes);
        }
    }

    @Test
    void rejectsUnknownAndPathLikeTemplateIds() {
        PersonalAppTemplateCatalog catalog = new PersonalAppTemplateCatalog(objectMapper);

        assertThat(catalog.find("missing-v1")).isEmpty();
        assertThat(catalog.find("../ai-daily-brief-v1")).isEmpty();
        assertThat(catalog.find("personal-app-templates/v1/ai-daily-brief.html")).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
    }

    @Test
    void missingOrTamperedClasspathResourceFailsCatalogConstruction() {
        ClassLoader emptyLoader = new ClassLoader(null) { };
        assertThatThrownBy(() -> new PersonalAppTemplateCatalog(
                objectMapper, emptyLoader, "missing-personal-app-templates"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template resource");

        ClassLoader parent = getClass().getClassLoader();
        ClassLoader tamperedLoader = new ClassLoader(parent) {
            @Override
            public InputStream getResourceAsStream(String name) {
                InputStream original = parent.getResourceAsStream(name);
                if (original == null || !name.endsWith("ai-daily-brief.html")) return original;
                try (original) {
                    byte[] bytes = original.readAllBytes();
                    byte[] changed = java.util.Arrays.copyOf(bytes, bytes.length + 1);
                    changed[changed.length - 1] = ' ';
                    return new ByteArrayInputStream(changed);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        assertThatThrownBy(() -> new PersonalAppTemplateCatalog(
                objectMapper, tamperedLoader, "personal-app-templates/v1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void referenceTemplatesAreSelfContainedAndCarryPlatformStateSchemas() {
        PersonalAppTemplateCatalog catalog = new PersonalAppTemplateCatalog(objectMapper);

        for (String templateId : catalog.templateIds()) {
            PersonalAppTemplateCatalog.Template template = catalog.find(templateId).orElseThrow();
            String html = new String(template.htmlBytes(), StandardCharsets.UTF_8);
            String normalized = html.toLowerCase();
            String css = html.substring(
                    html.indexOf("<style>") + "<style>".length(),
                    html.indexOf("</style>"));

            assertThat(template.manifest().stateSchema())
                    .containsEntry("type", "object")
                    .containsEntry("additionalProperties", false);
            assertThat(count(css, '{')).isEqualTo(count(css, '}'));
            assertThat(html)
                    .contains("--space-1: 8px", "--radius-sm: 12px", "--radius-md: 16px")
                    .contains("--radius-lg: 20px", "--target: 44px")
                    .contains("env(safe-area-inset-bottom)")
                    .contains("@media (prefers-color-scheme: dark)")
                    .contains("@media (prefers-reduced-motion: reduce)")
                    .contains("bridge.saveState(", "bridge.submitSnapshot(");
            assertThat(normalized)
                    .doesNotContain("http://", "https://", "fetch(", "xmlhttprequest",
                            "websocket", "eventsource", "window.open", "location.href",
                            "clipboard", "atob(", "btoa(", "base64", "eval(", "new function");
        }
    }

    @Test
    void priorityBadgeTokensMeetWcagAaForSmallTextInLightAndDarkModes() {
        PersonalAppTemplateCatalog catalog = new PersonalAppTemplateCatalog(objectMapper);
        String html = new String(catalog.find("ai-daily-brief-v1").orElseThrow().htmlBytes(),
                StandardCharsets.UTF_8);

        assertThat(html)
                .contains("--priority-important: #8f1d18")
                .contains("--priority-normal: #145c3d")
                .contains("--priority-important: #ff9d96")
                .contains("--priority-normal: #70d7aa");
        assertThat(contrastRatio("#8f1d18", "#ececf3")).isGreaterThanOrEqualTo(4.5);
        assertThat(contrastRatio("#145c3d", "#ececf3")).isGreaterThanOrEqualTo(4.5);
        assertThat(contrastRatio("#ff9d96", "#262630")).isGreaterThanOrEqualTo(4.5);
        assertThat(contrastRatio("#70d7aa", "#262630")).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void budgetStateUsesStableAmountsShapeAndValidatesDynamicCategoryKeys() {
        PersonalAppTemplateCatalog.Template template = new PersonalAppTemplateCatalog(objectMapper)
                .find(PersonalAppTemplateCatalog.BUDGET_PLANNER_ID).orElseThrow();
        Map<String, Object> schema = template.manifest().stateSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> amounts = (Map<String, Object>) properties.get("amounts");

        assertThat((List<String>) schema.get("required"))
                .containsExactlyInAnyOrder("income", "amounts", "note");
        assertThat(properties).containsOnlyKeys("income", "amounts", "note");
        assertThat(amounts).containsEntry("type", "object");
        assertThat((Map<String, Object>) amounts.get("additionalProperties"))
                .containsEntry("type", "number");

        Map<String, Object> dynamicSnapshot = Map.of(
                "income", 22000,
                "amounts", Map.of("rent", 7200, "groceries", 3100),
                "note", "保留动态分类");
        assertThat(new SchemaValidator().validate(
                objectMapper.valueToTree(dynamicSnapshot), objectMapper.valueToTree(schema))).isEmpty();

        String html = new String(template.htmlBytes(), StandardCharsets.UTF_8);
        assertThat(html)
                .contains("restored.amounts", "amounts: Object.fromEntries", "amounts: { ...state.amounts }")
                .doesNotContain("restored[category.key]", "payload[category.key]");
    }

    @Test
    void readStoryStyleDoesNotReduceTextContrastWithOpacityOrFilter() {
        String html = new String(new PersonalAppTemplateCatalog(objectMapper)
                .find(PersonalAppTemplateCatalog.AI_DAILY_BRIEF_ID).orElseThrow().htmlBytes(),
                StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "\\.story\\[data-read=[\\\"']true[\\\"']]\\s*\\{([^}]*)}",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);

        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group(1).toLowerCase())
                .doesNotContain("opacity", "filter");
    }

    private static long count(String value, char target) {
        return value.chars().filter(character -> character == target).count();
    }

    private static double contrastRatio(String foreground, String background) {
        double light = Math.max(luminance(foreground), luminance(background));
        double dark = Math.min(luminance(foreground), luminance(background));
        return (light + 0.05) / (dark + 0.05);
    }

    private static double luminance(String color) {
        int red = Integer.parseInt(color.substring(1, 3), 16);
        int green = Integer.parseInt(color.substring(3, 5), 16);
        int blue = Integer.parseInt(color.substring(5, 7), 16);
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
