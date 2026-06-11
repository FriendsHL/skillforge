package com.skillforge.server.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * HTTP-level regression for DESKTOP-MACOS-PACKAGE SPA serving — exercises the REAL
 * Spring MVC pipeline (DispatcherServlet → view controllers → ResourceHttpRequestHandler
 * → {@link SpaStaticConfig.SpaPathResourceResolver}).
 *
 * <p>The isolated resolver unit tests in {@link SpaStaticConfigTest} cannot catch the
 * root-path blocker: {@code GET /} produces an EMPTY pathWithinMapping, which
 * ResourceHttpRequestHandler 404s before the resolver chain ever runs. Only a request
 * through the real handler — as here — proves the {@code addViewController("/")} forward
 * actually serves the SPA shell.
 */
@SpringJUnitWebConfig(SpaStaticConfigHttpTest.WebConfig.class)
@DisplayName("SpaStaticConfig (HTTP pipeline)")
class SpaStaticConfigHttpTest {

    /** Created before the Spring context so {@code skillforge.spa.root} can point at it. */
    static final Path WEB_ROOT = createWebRoot();

    private static Path createWebRoot() {
        try {
            Path web = Files.createTempDirectory("spa-http-test");
            Files.writeString(web.resolve("index.html"),
                    "<!doctype html><html><body><div id=\"root\"></div></body></html>");
            Files.createDirectory(web.resolve("assets"));
            Files.writeString(web.resolve("assets").resolve("app.js"), "console.log('app')");
            return web;
        } catch (IOException e) {
            throw new IllegalStateException("failed to set up SPA test root", e);
        }
    }

    @DynamicPropertySource
    static void spaRoot(DynamicPropertyRegistry registry) {
        registry.add("skillforge.spa.root", WEB_ROOT::toString);
    }

    @Configuration
    @EnableWebMvc
    @Import(SpaStaticConfig.class)
    static class WebConfig {
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp(@Autowired WebApplicationContext wac) {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    @DisplayName("GET / → 200, forwards to /index.html (root-path blocker regression)")
    void root_servesSpaShell() throws Exception {
        // Without the view controller this 404s. MockMvc records the forward target
        // but does not follow it; the forwarded /index.html is served in the test below.
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("GET /index.html → 200 + SPA shell body")
    void indexHtml_served() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"root\"")));
    }

    @Test
    @DisplayName("GET /assets/app.js → 200 + asset body")
    void asset_served() throws Exception {
        mvc.perform(get("/assets/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("console.log")));
    }

    @Test
    @DisplayName("GET /sessions/x → 200 + SPA shell (deep-link fallback through real handler)")
    void deepLink_fallsBackToIndex() throws Exception {
        mvc.perform(get("/sessions/x"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"root\"")));
    }

    @Test
    @DisplayName("GET /api/whatever → not swallowed by SPA (404, body not the shell)")
    void apiPath_notSwallowed() throws Exception {
        // No controller/auth interceptor in this slice, so the catch-all resource
        // handler sees it; the resolver returns null for the api/ prefix → 404,
        // never the SPA shell.
        mvc.perform(get("/api/whatever"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("id=\"root\""))));
    }
}
