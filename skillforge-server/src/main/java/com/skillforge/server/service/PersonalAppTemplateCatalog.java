package com.skillforge.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.artifact.InteractiveArtifactManifest;
import com.skillforge.server.artifact.InteractiveArtifactValidator;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PersonalAppTemplateCatalog {

    public static final String AI_DAILY_BRIEF_ID = "ai-daily-brief-v1";
    public static final String BUDGET_PLANNER_ID = "budget-planner-v1";

    private static final String DEFAULT_RESOURCE_ROOT = "personal-app-templates/v1";
    private static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;
    private static final List<TemplateSpec> SPECS = List.of(
            new TemplateSpec(
                    AI_DAILY_BRIEF_ID,
                    "ai-daily-brief.html",
                    "ai-daily-brief.manifest.json",
                    "c970bee720db6eedff6cf166ef0d9705147d25bfa01a94348a003b9eb55b2a2a",
                    "a48b8ccc796e6ae66c43775beb28f4ea981cff753d55c191a319a66ef570b2dd"),
            new TemplateSpec(
                    BUDGET_PLANNER_ID,
                    "budget-planner.html",
                    "budget-planner.manifest.json",
                    "f671220872f67b43dc274b30a9c7e322e68eb3cdc0b0c8fadeccc0577f7afa24",
                    "8ce8d55da0c0671663301b34477adb1b3f09ab46eb5b07bca09a0138b49889c0"));

    private final ObjectMapper objectMapper;
    private final Map<String, StoredTemplate> templates;
    private final List<String> templateIds;

    public PersonalAppTemplateCatalog(ObjectMapper objectMapper) {
        this(objectMapper, PersonalAppTemplateCatalog.class.getClassLoader(), DEFAULT_RESOURCE_ROOT);
    }

    PersonalAppTemplateCatalog(ObjectMapper objectMapper, ClassLoader classLoader, String resourceRoot) {
        if (objectMapper == null || classLoader == null || resourceRoot == null || resourceRoot.isBlank()) {
            throw new IllegalArgumentException("Personal App template catalog configuration is required");
        }
        this.objectMapper = objectMapper;
        this.templates = loadTemplates(classLoader, resourceRoot);
        this.templateIds = List.copyOf(new ArrayList<>(templates.keySet()));
    }

    public List<String> templateIds() {
        return templateIds;
    }

    public Optional<Template> find(String templateId) {
        if (templateId == null) return Optional.empty();
        StoredTemplate stored = templates.get(templateId);
        if (stored == null) return Optional.empty();
        return Optional.of(new Template(
                stored.id(), stored.filename(), stored.htmlBytes(), parseManifest(stored.manifestBytes())));
    }

    private Map<String, StoredTemplate> loadTemplates(ClassLoader classLoader, String resourceRoot) {
        Map<String, StoredTemplate> loaded = new LinkedHashMap<>();
        InteractiveArtifactValidator validator = new InteractiveArtifactValidator(objectMapper);
        for (TemplateSpec spec : SPECS) {
            byte[] html = readResource(classLoader, resourceRoot, spec.htmlFilename());
            byte[] manifestBytes = readResource(classLoader, resourceRoot, spec.manifestFilename());
            requireHash(html, spec.htmlSha256(), spec.htmlFilename());
            requireHash(manifestBytes, spec.manifestSha256(), spec.manifestFilename());
            InteractiveArtifactManifest manifest = parseManifest(manifestBytes);
            try {
                validator.validate(manifest, html);
            } catch (IllegalArgumentException e) {
                throw new SecurityException(
                        "Personal App template resource failed validation: " + spec.id(), e);
            }
            loaded.put(spec.id(), new StoredTemplate(
                    spec.id(), spec.htmlFilename(), html.clone(), manifestBytes.clone()));
        }
        return java.util.Collections.unmodifiableMap(loaded);
    }

    private InteractiveArtifactManifest parseManifest(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, InteractiveArtifactManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid Personal App template resource manifest", e);
        }
    }

    private static byte[] readResource(ClassLoader classLoader, String root, String filename) {
        String path = root + "/" + filename;
        try (InputStream input = classLoader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing Personal App template resource: " + filename);
            }
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_RESOURCE_BYTES) {
                throw new IllegalStateException("Invalid Personal App template resource: " + filename);
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Personal App template resource: " + filename, e);
        }
    }

    private static void requireHash(byte[] bytes, String expected, String filename) {
        String actual;
        try {
            actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        if (!expected.equals(actual)) {
            throw new SecurityException("Personal App template resource integrity check failed: " + filename);
        }
    }

    public record Template(
            String id,
            String filename,
            byte[] htmlBytes,
            InteractiveArtifactManifest manifest) {

        public Template {
            htmlBytes = htmlBytes.clone();
        }

        @Override
        public byte[] htmlBytes() {
            return htmlBytes.clone();
        }
    }

    private record TemplateSpec(
            String id,
            String htmlFilename,
            String manifestFilename,
            String htmlSha256,
            String manifestSha256) { }

    private record StoredTemplate(
            String id,
            String filename,
            byte[] htmlBytes,
            byte[] manifestBytes) {

        @Override
        public byte[] htmlBytes() {
            return htmlBytes.clone();
        }

        @Override
        public byte[] manifestBytes() {
            return manifestBytes.clone();
        }
    }
}
