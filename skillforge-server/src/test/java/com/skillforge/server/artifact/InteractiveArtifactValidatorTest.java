package com.skillforge.server.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractiveArtifactValidatorTest {

    private final InteractiveArtifactValidator validator =
            new InteractiveArtifactValidator(new ObjectMapper());

    @Test
    void acceptsOfflineSelfContainedHtmlAndBoundedManifest() {
        var manifest = new InteractiveArtifactManifest(
                1,
                "July budget",
                "An offline adjustable budget planner.",
                List.of(),
                List.of(),
                Map.of("food", 2600),
                Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of("food", Map.of("type", "integer"))));

        assertThatCode(() -> validator.validate(manifest, fixture("budget-valid.html")))
                .doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void manifestDefensivelyCopiesNestedJsonWhilePreservingNull() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("subtitle", null);
        List<Object> items = new ArrayList<>();
        items.add(nested);
        Map<String, Object> initialData = new LinkedHashMap<>();
        initialData.put("items", items);

        InteractiveArtifactManifest manifest = new InteractiveArtifactManifest(
                1, "Title", "Fallback", List.of(), List.of(), initialData,
                Map.of("type", "object"));
        nested.put("later", "mutation");
        items.add("mutation");
        initialData.put("later", "mutation");

        Map<String, Object> copiedNested = (Map<String, Object>)
                ((List<?>) manifest.initialData().get("items")).get(0);
        assertThat(manifest.initialData()).containsOnlyKeys("items");
        assertThat((List<?>) manifest.initialData().get("items")).hasSize(1);
        assertThat(copiedNested).containsEntry("subtitle", null).doesNotContainKey("later");
        assertThatThrownBy(() -> manifest.initialData().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> copiedNested.put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsUrlsWhenTheyAreInertContentOrData() {
        assertThatCode(() -> validator.validate(validManifest(), fixture("inert-url-valid.html")))
                .doesNotThrowAnyException();

        String prefix = "https://example.com/";
        String maximumLength = prefix + "a".repeat(2_048 - prefix.getBytes(StandardCharsets.UTF_8).length);
        for (String url : List.of(
                "http://example.com",
                "HTTPS://Example.COM:8443/a%20b?q=hello%20world#details",
                maximumLength)) {
            assertThatCode(() -> validator.validate(validManifest(), html(
                    "<article data-sf-url=\"" + url + "\">Story</article>")))
                    .as(url)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void deeplyNestedHtmlIsScannedWithoutLeakingAStackOverflowError() {
        String html = "<!doctype html><html><body>"
                + "<div>".repeat(20_000)
                + "content"
                + "</div>".repeat(20_000)
                + "</body></html>";

        assertThatCode(() -> validator.validate(
                validManifest(), html.getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
    }

    @Test
    void dataSfUrlUsesTheSameFailClosedBoundaryAsTheIosOpenUrlPolicy() {
        String accepted = "https://example.com/" + "a".repeat(
                2_048 - "https://example.com/".getBytes(StandardCharsets.UTF_8).length);
        for (String url : List.of(
                "",
                "relative/path",
                "//example.com/path",
                "javascript:alert(1)",
                "data:text/html,hello",
                "file:///tmp/report.html",
                "mailto:user@example.com",
                "https:///missing-host",
                "http:example.com",
                "https://user@example.com/report",
                "https://user:secret@example.com/report",
                "https://example.com/line\nbreak",
                "https://example.com/line\u0000break",
                " https://example.com",
                "https%3A%2F%2Fexample.com",
                "https://example.com/%ZZ",
                "https://example.com\\@evil.example",
                accepted + "a")) {
            assertRejected("data-sf-url: " + url, "<article data-sf-url=\""
                    + url.replace("\"", "&quot;") + "\">Story</article>");
        }
    }

    @Test
    void dataSfUrlUsesTheSharedConservativeAsciiHostCorpus() {
        for (String url : List.of(
                "https://localhost/report",
                "https://xn--fsqu00a.xn--0zwm56d/report",
                "https://127.0.0.1/report")) {
            assertThatCode(() -> validator.validate(validManifest(), html(
                    "<article data-sf-url=\"" + url + "\">Story</article>")))
                    .as(url)
                    .doesNotThrowAnyException();
        }

        for (String url : List.of(
                "https://foo_bar.com/report",
                "https://%65xample.com/report",
                "https://例子.com/report",
                "https://.example.com/report",
                "https://example..com/report",
                "https://-example.com/report",
                "https://example-.com/report",
                "https://127.1/report",
                "https://01.2.3.4/report",
                "https://010.0.0.1/report",
                "https://1.02.3.4/report",
                "https://123/report",
                "https://256.0.0.1/report",
                "https://[::1]/report")) {
            assertRejected("data-sf-url host: " + url,
                    "<article data-sf-url=\"" + url + "\">Story</article>");
        }
    }

    @Test
    void rejectsUrlLiteralsOutsideTheThreeExplicitInertSlots() {
        assertRejected("ordinary-attribute-url",
                "<article title=\"https://example.com/story\">Story</article>");
        assertRejected("ordinary-attribute-protocol-relative-url",
                "<article title=\"source //evil.invalid/story\">Story</article>");
        assertRejected("comment-url", "<!-- https://example.com/story -->");
        assertRejected("comment-protocol-relative-url", "<!-- source //evil.invalid/story -->");
        assertRejected("css-image-set-string-url", """
                <style>.hero { background: image-set("https://example.com/hero.png" 1x); }</style>
                """);
        assertRejected("css-image-set-protocol-relative-url", """
                <style>.hero { background: image-set("//evil.invalid/hero.png" 1x); }</style>
                """);
    }

    @Test
    void rejectsEveryMaliciousFixture() {
        var manifest = validManifest();
        for (String fixture : List.of(
                "external-fetch.html",
                "remote-resource.html",
                "navigation-escape.html",
                "active-attribute-variants.html",
                "active-css.html",
                "active-apis.html",
                "device-permission.html",
                "active-navigation.html",
                "active-obfuscation.html")) {
            assertThatThrownBy(() -> validator.validate(manifest, fixture(fixture)))
                    .as(fixture)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsActiveTagsRefreshAndExternalOrDangerousAttributesAfterHtmlDecoding() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("iframe", "<iframe></iframe>");
        cases.put("object", "<object></object>");
        cases.put("embed", "<embed>");
        cases.put("portal", "<portal></portal>");
        cases.put("base", "<base href='/nested/'>");
        cases.put("meta-refresh-case", "<MeTa HTTP-Equiv='ReFrEsH' content='0; /next'>");
        cases.put("entity-and-case-src", "<img SrC='&#x68;TTps://evil.invalid/pixel'>");
        cases.put("srcset", "<img srcset='https://evil.invalid/a 1x, /safe.png 2x'>");
        cases.put("anchor-href", "<a href='javascript&#x3a;alert(1)'>go</a>");
        cases.put("xlink-href", "<svg><use xlink:href='https://evil.invalid/icon.svg#x'></use></svg>");
        cases.put("action", "<form action='//evil.invalid/submit'></form>");
        cases.put("formaction", "<button formaction='file:///tmp/leak'>send</button>");
        cases.put("poster", "<video poster='https://evil.invalid/poster.png'></video>");
        cases.put("file-input", "<input type='file'>");
        cases.put("file-input-case-entity", "<InPuT TyPe='&#x66;IlE'>");
        cases.put("capture-attribute", "<input type='text' capture>");
        cases.put("capture-attribute-case", "<input CaPtUrE='environment'>");
        cases.put("script-relative-src", "<script src='/active.js'></script>");
        cases.put("link-relative-href", "<link rel='stylesheet' href='/active.css'>");

        cases.forEach((name, body) -> assertRejected(name, body));
    }

    @Test
    void rejectsRemoteCssAndEveryActiveScriptCapability() {
        Map<String, String> cssCases = Map.of(
                "css-import-relative", "<style>@import url('/active.css');</style>",
                "css-import-string", "<style>@import 'https://evil.invalid/a.css';</style>",
                "css-remote-url", "<style>.x{background:url(//evil.invalid/pixel)}</style>",
                "inline-style-remote-url", "<div style=\"background:url('https://evil.invalid/pixel')\"></div>",
                "css-escaped-import", "<style>@im\\70ort '/active.css';</style>",
                "css-escaped-url", "<style>.x{background:u\\72l('/pixel')}</style>",
                "css-comment-split-url", "<style>.x{background:u/**/rl('/pixel')}</style>");
        cssCases.forEach((name, body) -> assertRejected(name, body));

        Map<String, String> scriptCases = new LinkedHashMap<>();
        scriptCases.put("fetch-whitespace", "window . fetch ('/leak')");
        scriptCases.put("fetch-comment", "window /*x*/ . fetch('/leak')");
        scriptCases.put("fetch-unicode", "f\\u0065tch('/leak')");
        scriptCases.put("xhr-case", "new xmlHTTPrequest()");
        scriptCases.put("websocket", "new WebSocket('/socket')");
        scriptCases.put("event-source", "new EventSource('/events')");
        scriptCases.put("send-beacon", "navigator . sendBeacon('/audit')");
        scriptCases.put("send-beacon-bracket", "navigator[\"sendBeacon\"]('/audit')");
        scriptCases.put("dynamic-import", "import ('/module.js')");
        scriptCases.put("dynamic-import-comment", "import /*x*/ ('/module.js')");
        scriptCases.put("dynamic-import-line-comment", "import // split\n ('/module.js')");
        scriptCases.put("import-scripts", "importScripts ('/worker.js')");
        scriptCases.put("window-open-whitespace", "window . open('/next')");
        scriptCases.put("window-open-optional-chain", "window?.open('/next')");
        scriptCases.put("window-open-line-comment", "window // split\n . open('/next')");
        scriptCases.put("window-open-template-expression", "const label = `${window // split\n . open('/next')}`");
        scriptCases.put("window-open-after-slashes-in-string",
                "const label = 'not // a comment'; window?.open('/next')");
        scriptCases.put("window-location", "window . location = '/next'");
        scriptCases.put("location-method", "location . replace('/next')");
        scriptCases.put("location-bracket", "location[\"replace\"]('/next')");
        scriptCases.put("clipboard", "navigator . clipboard . writeText('secret')");
        scriptCases.put("atob", "atob ('ZmV0Y2g=')");
        scriptCases.put("btoa", "btoa ('secret')");
        scriptCases.put("eval", "eval ('code')");
        scriptCases.put("function-constructor", "new Function ('return 1')");
        scriptCases.put("from-char-code", "String . fromCharCode(102,101,116,99,104)");
        scriptCases.put("from-char-code-line-comment", "String // split\n . fromCharCode(102)");
        scriptCases.put("from-code-point", "String . fromCodePoint(102,101,116,99,104)");
        scriptCases.put("document-write", "document . write('<p>dynamic</p>')");
        scriptCases.put("set-active-attribute", "node.setAttribute('src', '/pixel')");
        scriptCases.put("exec-command-copy", "document.execCommand('copy')");
        scriptCases.put("string-timeout", "setTimeout('doWork()', 10)");
        scriptCases.put("string-interval", "setInterval(\"doWork()\", 10)");
        scriptCases.put("dom-parser", "new DOMParser().parseFromString(markup, 'text/html')");
        scriptCases.put("inner-html", "node.innerHTML = '<p>dynamic</p>'");
        scriptCases.put("worker", "new Worker('/worker.js')");
        scriptCases.put("shared-worker", "new SharedWorker('/worker.js')");
        scriptCases.put("service-worker", "navigator.serviceWorker.register('/worker.js')");
        scriptCases.put("media-devices", "navigator.mediaDevices.getUserMedia({audio:true})");
        scriptCases.put("legacy-get-user-media", "navigator.webkitGetUserMedia({video:true}, ok, fail)");
        scriptCases.put("geolocation", "navigator.geolocation.getCurrentPosition(done)");
        scriptCases.put("remote-url-literal", "const story = 'https://example.com/story'");

        scriptCases.forEach((name, script) ->
                assertRejected(name, "<script>" + script + "</script>"));
    }

    @Test
    void applicationJsonMustActuallyContainJson() {
        assertRejected("invalid-application-json", """
                <script type="application/json">
                  const notJson = "https://example.com/story";
                </script>
                """);
    }

    @Test
    void rejectsAllInlineEventHandlersAfterHtmlEntityDecoding() {
        assertRejected("all-inline-handlers", "<button onclick=\"toggle()\">Go</button>");
        assertRejected("event-fetch-entity",
                "<button ONCLICK=\"fetch&#40;'/leak'&#41;\">Go</button>");
        assertRejected("event-obfuscation-whitespace",
                "<button onpointerdown=\"String . fromCharCode(102)\">Go</button>");
    }

    @Test
    void rejectsPermissionsNetworkAndOversizedStateSchema() {
        assertThatThrownBy(() -> validator.validate(
                new InteractiveArtifactManifest(1, "Title", "Fallback",
                        List.of("camera"), List.of(), Map.of(), Map.of("type", "object")),
                fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions");

        assertThatThrownBy(() -> validator.validate(
                new InteractiveArtifactManifest(1, "Title", "Fallback",
                        List.of(), List.of("https://example.com"), Map.of(), Map.of("type", "object")),
                fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("network");

        assertThatThrownBy(() -> validator.validate(
                new InteractiveArtifactManifest(1, "Title", "Fallback",
                        List.of(), List.of(), Map.of("payload", "x".repeat(33 * 1024)),
                        Map.of("type", "object")), fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialData exceeds size limit");

        assertThatThrownBy(() -> validator.validate(
                new InteractiveArtifactManifest(1, "Title", "Fallback",
                        List.of(), List.of(), Map.of(),
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "x".repeat(17 * 1024), Map.of("type", "string")))),
                fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateSchema exceeds size limit");
    }

    @Test
    void titleAndFallbackRawLengthsCannotBeBypassedWithTrailingWhitespace() {
        InteractiveArtifactManifest oversizedTitle = new InteractiveArtifactManifest(
                1, "T" + " ".repeat(80), "Fallback", List.of(), List.of(), Map.of(),
                Map.of("type", "object"));
        InteractiveArtifactManifest oversizedFallback = new InteractiveArtifactManifest(
                1, "Title", "F" + " ".repeat(500), List.of(), List.of(), Map.of(),
                Map.of("type", "object"));

        assertThatThrownBy(() -> validator.validate(oversizedTitle, fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title length is invalid");
        assertThatThrownBy(() -> validator.validate(oversizedFallback, fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallback length is invalid");
    }

    @Test
    void acceptsOnlyTheStateSchemaSubsetImplementedByIos() {
        Map<String, Object> supported = Map.of(
                "type", "object",
                "required", List.of("name", "amounts", "rows"),
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "amounts", Map.of(
                                "type", "object",
                                "additionalProperties", Map.of("type", "number")),
                        "rows", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "count", Map.of("type", "integer"),
                                                "enabled", Map.of("type", "boolean")),
                                        "additionalProperties", false))),
                "additionalProperties", true);

        assertThatCode(() -> validator.validate(manifestWithStateSchema(supported),
                fixture("budget-valid.html"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedOrMalformedStateSchemaBeforePublishing() {
        Map<String, Map<String, Object>> invalid = new LinkedHashMap<>();
        invalid.put("unsupported-root-keyword", Map.of(
                "type", "object", "minimum", 0));
        invalid.put("unsupported-nested-keyword", Map.of(
                "type", "object",
                "properties", Map.of("amount", Map.of("type", "number", "minimum", 0))));
        invalid.put("unsupported-type", Map.of(
                "type", "object",
                "properties", Map.of("amount", Map.of("type", "currency"))));
        invalid.put("properties-not-object", Map.of(
                "type", "object", "properties", List.of()));
        invalid.put("property-not-schema", Map.of(
                "type", "object", "properties", Map.of("amount", "number")));
        invalid.put("required-not-string-array", Map.of(
                "type", "object", "required", List.of(1)));
        invalid.put("additional-properties-not-boolean-or-schema", Map.of(
                "type", "object", "additionalProperties", "yes"));
        invalid.put("items-not-schema", Map.of(
                "type", "object",
                "properties", Map.of("rows", Map.of("type", "array", "items", "object"))));
        invalid.put("scalar-with-object-keyword", Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of(
                        "type", "string", "properties", Map.of()))));

        invalid.forEach((name, schema) -> assertThatThrownBy(() -> validator.validate(
                manifestWithStateSchema(schema), fixture("budget-valid.html")))
                .as(name)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateSchema"));
    }

    @Test
    void enforcesTheIosSchemaNodeBoundaryAt1024Values() {
        Map<String, Object> boundary = Map.of(
                "type", "object",
                "required", Collections.nCopies(1_021, "field"));
        Map<String, Object> overBoundary = Map.of(
                "type", "object",
                "required", Collections.nCopies(1_022, "field"));

        assertThatCode(() -> validator.validate(
                manifestWithStateSchema(boundary), fixture("budget-valid.html")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                manifestWithStateSchema(overBoundary), fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateSchema exceeds maximum node count");
    }

    @Test
    void rejectsDeepSchemaAtTheTraversalEntryBeforeJsonSerialization() {
        Map<String, Object> nested = Map.of("type", "string");
        for (int i = 0; i < 2_000; i++) {
            nested = Map.of("type", "array", "items", nested);
        }
        Map<String, Object> deeplyNestedSchema = Map.of(
                "type", "object",
                "properties", Map.of("nested", nested));

        assertThatThrownBy(() -> validator.validate(
                manifestWithStateSchema(deeplyNestedSchema), fixture("budget-valid.html")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stateSchema exceeds maximum depth");
    }

    private static InteractiveArtifactManifest validManifest() {
        return new InteractiveArtifactManifest(1, "Title", "Fallback", List.of(), List.of(),
                Map.of(), Map.of("type", "object", "additionalProperties", false,
                        "properties", Map.of()));
    }

    private static InteractiveArtifactManifest manifestWithStateSchema(Map<String, Object> schema) {
        return new InteractiveArtifactManifest(
                1, "Title", "Fallback", List.of(), List.of(), Map.of(), schema);
    }

    private void assertRejected(String name, String body) {
        assertThatThrownBy(() -> validator.validate(validManifest(), html(body)))
                .as(name)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    private static byte[] html(String body) {
        return ("<!doctype html><html><head><meta charset='utf-8'></head><body>"
                + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fixture(String name) {
        try (var stream = InteractiveArtifactValidatorTest.class.getResourceAsStream(
                "/interactive-artifacts/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing fixture " + name);
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
