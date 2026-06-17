package com.skillforge.server.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * DESKTOP-MACOS-PACKAGE D2 — serve the built dashboard SPA directly from the Spring
 * server so the desktop bundle is same-origin (FE keeps {@code baseURL: '/api'} and
 * {@code window.location.host} WS URLs with zero changes).
 *
 * <p><b>Dev safety</b>: gated by {@link ConditionalOnProperty} on
 * {@code skillforge.spa.root}. When the property is absent (every dev / test run),
 * this bean is never created and resource-handler behaviour is identical to before
 * (server serves no static SPA). The desktop launcher passes
 * {@code --skillforge.spa.root=<Resources/web>} to flip it on.
 *
 * <p>Routing precedence: {@code @RestController} {@code /api/**} handlers and the
 * WebSocket {@code /ws/**} handler are registered with higher precedence than this
 * catch-all resource handler, so live API/WS routes are never shadowed. As
 * defense-in-depth the {@link PathResourceResolver} below still refuses to fall back
 * to {@code index.html} for {@code api/} and {@code ws/} prefixes, so an unmapped
 * {@code /api/...} path 404s instead of leaking the SPA shell.
 */
@Configuration
@ConditionalOnProperty(name = "skillforge.spa.root")
public class SpaStaticConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SpaStaticConfig.class);

    private final String spaRoot;

    public SpaStaticConfig(Environment environment) {
        this.spaRoot = environment.getProperty("skillforge.spa.root", "");
        // fail-fast: an empty `--skillforge.spa.root=` would mount the entire
        // filesystem root at /** (fail-dangerous footgun). @ConditionalOnProperty
        // only checks presence, not non-emptiness, so guard here.
        Assert.hasText(this.spaRoot, "skillforge.spa.root must not be empty when set");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Trailing slash is REQUIRED: Spring resolves requested paths relative to the
        // location, and a missing slash makes the last segment a filename prefix.
        String location = "file:" + (spaRoot.endsWith("/") ? spaRoot : spaRoot + "/");
        log.info("SPA static serving enabled: /** -> {}", location);

        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new SpaPathResourceResolver());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // GET "/" maps to an EMPTY pathWithinMapping; ResourceHttpRequestHandler
        // bails with 404 on `!StringUtils.hasText(path)` BEFORE the resolver chain
        // runs (so SpaPathResourceResolver never sees the root). Explicitly forward
        // "/" to "/index.html" — the resource handler then serves the SPA entry with
        // a non-empty path. The desktop launcher's health poll of "/" depends on
        // this returning 200 + the SPA shell.
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    /**
     * Serve the requested file when it exists and is readable; otherwise fall back to
     * {@code index.html} so client-side routes (e.g. {@code /sessions/x}, {@code /login})
     * deep-link / full-page-reload correctly. API and WS prefixes never fall back.
     */
    static final class SpaPathResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // Don't mask API / WS routes with the SPA shell — let them 404 naturally.
            // Relies on Spring's PathPatternParser (Spring Boot 3.2 default; no
            // spring.mvc.pathmatch.matching-strategy=ant_path_matcher override in
            // application.yml) passing resourcePath WITHOUT a leading slash, so the
            // "api/" / "ws/" prefix check is correct.
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws/")) {
                return null;
            }
            // Delegate to the superclass so its location-containment check
            // (checkResource) runs — defense-in-depth against path traversal.
            Resource resource = super.getResource(resourcePath, location);
            // super.getResource returns a readable DIRECTORY Resource for the root
            // ("") and sub-dir paths — Spring can't serve a directory (→ 404), so
            // those must be treated as a miss and fall back to index.html (the SPA
            // entry). Only serve when it resolves to an actual file.
            if (resource != null && resource.isReadable() && isServableFile(resource)) {
                return resource;
            }
            // SPA deep-link fallback (root "/", /sessions/x, /login, …).
            return super.getResource("index.html", location);
        }

        /** True only for an existing regular file (excludes directories). */
        private static boolean isServableFile(Resource resource) {
            try {
                return resource.getFile().isFile();
            } catch (IOException e) {
                // Non-file: resource (shouldn't happen for a file: location) —
                // be conservative and treat as a miss.
                return false;
            }
        }
    }
}
