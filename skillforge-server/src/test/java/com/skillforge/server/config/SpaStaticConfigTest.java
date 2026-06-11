package com.skillforge.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * DESKTOP-MACOS-PACKAGE D2 — locks {@link SpaStaticConfig} behaviour:
 * <ul>
 *   <li>existing static file is served as-is;</li>
 *   <li>unknown client route falls back to {@code index.html} (SPA deep-link);</li>
 *   <li>{@code api/} and {@code ws/} prefixes never fall back to the SPA shell;</li>
 *   <li>the config bean is gated by the {@code skillforge.spa.root} property
 *       (absent in dev → zero behaviour change).</li>
 * </ul>
 */
@DisplayName("SpaStaticConfig")
class SpaStaticConfigTest {

    private final SpaStaticConfig.SpaPathResourceResolver resolver =
            new SpaStaticConfig.SpaPathResourceResolver();

    private static Resource dirLocation(Path dir) {
        // Trailing slash so createRelative resolves into the directory.
        return new FileSystemResource(dir.toString() + "/");
    }

    @Test
    @DisplayName("serves an existing static asset verbatim")
    void servesExistingAsset(@TempDir Path web) throws IOException {
        Files.writeString(web.resolve("index.html"), "<div id=\"root\"></div>");
        Files.writeString(web.resolve("app.js"), "console.log(1)");

        Resource resolved = resolver.getResource("app.js", dirLocation(web));

        assertThat(resolved).isNotNull();
        assertThat(resolved.isReadable()).isTrue();
        assertThat(resolved.getFilename()).isEqualTo("app.js");
    }

    @Test
    @DisplayName("resolver: empty path resolves to index.html, NOT the directory resource (defensive)")
    void rootPathReturnsIndexHtml(@TempDir Path web) throws IOException {
        // NOTE: in the real pipeline GET "/" never reaches the resolver with "" —
        // ResourceHttpRequestHandler 404s the empty path first, so SpaStaticConfig
        // forwards "/" → "/index.html" via a view controller (see
        // SpaStaticConfigHttpTest#root_servesSpaShell). This test only pins the
        // resolver's defensive directory→index.html behaviour.
        Files.writeString(web.resolve("index.html"), "<div id=\"root\"></div>");
        Files.writeString(web.resolve("app.js"), "console.log(1)");

        Resource resolved = resolver.getResource("", dirLocation(web));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getFilename()).isEqualTo("index.html");
        assertThat(resolved.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("id=\"root\"");
    }

    @Test
    @DisplayName("serves a nested static asset (assets/foo.js)")
    void servesNestedAsset(@TempDir Path web) throws IOException {
        Files.writeString(web.resolve("index.html"), "<div id=\"root\"></div>");
        Files.createDirectory(web.resolve("assets"));
        Files.writeString(web.resolve("assets").resolve("foo.js"), "export const x = 1");

        Resource resolved = resolver.getResource("assets/foo.js", dirLocation(web));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getFilename()).isEqualTo("foo.js");
        assertThat(resolved.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("export const x");
    }

    @Test
    @DisplayName("a sub-directory path falls back to index.html (directory resource is not servable)")
    void subDirectoryPathFallsBackToIndex(@TempDir Path web) throws IOException {
        Files.writeString(web.resolve("index.html"), "<div id=\"root\"></div>");
        Files.createDirectory(web.resolve("assets"));

        // "assets" resolves to a readable directory — must not be served as-is.
        Resource resolved = resolver.getResource("assets", dirLocation(web));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getFilename()).isEqualTo("index.html");
    }

    @Test
    @DisplayName("unknown client route falls back to index.html (SPA deep-link)")
    void deepLinkFallsBackToIndex(@TempDir Path web) throws IOException {
        Files.writeString(web.resolve("index.html"), "<div id=\"root\"></div>");

        Resource resolved = resolver.getResource("sessions/abc", dirLocation(web));

        assertThat(resolved).isNotNull();
        assertThat(resolved.getFilename()).isEqualTo("index.html");
        assertThat(resolved.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("id=\"root\"");
    }

    @Test
    @DisplayName("api/ prefix is never masked by the SPA shell (returns null → 404, not index.html)")
    void apiPrefixNotSwallowed(@TempDir Path web) throws IOException {
        Files.writeString(web.resolve("index.html"), "<div id=\"root\"></div>");

        Resource resolved = resolver.getResource("api/agents", dirLocation(web));

        assertThat(resolved).isNull();
    }

    @Test
    @DisplayName("ws/ prefix is never masked by the SPA shell")
    void wsPrefixNotSwallowed(@TempDir Path web) throws IOException {
        Files.writeString(web.resolve("index.html"), "<div id=\"root\"></div>");

        Resource resolved = resolver.getResource("ws/session/1", dirLocation(web));

        assertThat(resolved).isNull();
    }

    @Test
    @DisplayName("missing index.html → null rather than a broken resource")
    void noIndexHtmlReturnsNull(@TempDir Path web) throws IOException {
        Resource resolved = resolver.getResource("sessions/abc", dirLocation(web));

        assertThat(resolved).isNull();
    }

    // ── @ConditionalOnProperty gating (dev = property absent = bean absent) ──

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(
                    org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            WebMvcAutoConfiguration.class))
            .withUserConfiguration(SpaStaticConfig.class);

    @Test
    @DisplayName("property absent → SpaStaticConfig bean not created (dev behaviour unchanged)")
    void beanAbsentWithoutProperty() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SpaStaticConfig.class));
    }

    @Test
    @DisplayName("property present → SpaStaticConfig bean created")
    void beanPresentWithProperty(@TempDir Path web) {
        runner.withPropertyValues("skillforge.spa.root=" + web)
                .run(ctx -> assertThat(ctx).hasSingleBean(SpaStaticConfig.class));
    }
}
