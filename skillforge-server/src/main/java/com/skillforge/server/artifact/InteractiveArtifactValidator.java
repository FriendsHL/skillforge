package com.skillforge.server.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InteractiveArtifactValidator {

    public static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    static final int MAX_EXTERNAL_URL_BYTES = 2_048;
    static final int MAX_INITIAL_DATA_BYTES = 32 * 1024;
    static final int MAX_INITIAL_DATA_DEPTH = 64;
    static final int MAX_STATE_SCHEMA_BYTES = 16 * 1024;
    static final int MAX_SCHEMA_DEPTH = 8;
    static final int MAX_SCHEMA_NODES = 1_024;

    private static final Set<String> FORBIDDEN_TAGS =
            Set.of("iframe", "object", "embed", "portal", "base");
    private static final Set<String> ACTIVE_REFERENCE_ATTRIBUTES =
            Set.of("src", "srcset", "href", "xlink:href", "action", "formaction", "poster", "ping");
    private static final Set<String> SUPPORTED_SCHEMA_TYPES =
            Set.of("string", "number", "integer", "boolean", "object", "array");
    private static final Set<String> OBJECT_SCHEMA_KEYWORDS =
            Set.of("type", "properties", "required", "additionalProperties");
    private static final Set<String> ARRAY_SCHEMA_KEYWORDS = Set.of("type", "items");
    private static final Set<String> SCALAR_SCHEMA_KEYWORDS = Set.of("type");

    private static final Pattern CSS_IMPORT = pattern("@\\s*import\\b");
    private static final Pattern CSS_URL = pattern("\\burl\\s*\\(\\s*(['\"]?)(.*?)\\1\\s*\\)");
    private static final Pattern BLOCK_COMMENT = pattern("/\\*.*?\\*/");
    private static final Pattern BRACKET_PROPERTY = Pattern.compile(
            "\\[\\s*(['\"])([A-Za-z_$][A-Za-z0-9_$]*)\\1\\s*\\]", Pattern.DOTALL);
    private static final Pattern URL_LITERAL = pattern(
            "\\b(?:https?|wss?|ws|file)\\s*:\\s*//|\\b(?:javascript|vbscript|data|blob|about|mailto|tel)\\s*:"
    );
    private static final Pattern PROTOCOL_RELATIVE_URL = pattern("//[^/\\s?#]");
    private static final Pattern EXECUTABLE_PROTOCOL_RELATIVE_URL = pattern("['\"`]//[^/]");
    private static final List<Pattern> FORBIDDEN_EXECUTABLE_SCRIPT = List.of(
            pattern("\\b(?:(?:window|globalThis|self)\\s*\\.\\s*)?fetch\\s*\\("),
            pattern("\\b(?:XMLHttpRequest|WebSocket|EventSource)\\b"),
            pattern("\\b(?:(?:navigator|window\\s*\\.\\s*navigator)\\s*\\.\\s*)?sendBeacon\\s*\\("),
            pattern("\\bimport\\s*(?:\\(|['\"]|Scripts\\s*\\()"),
            pattern("\\b(?:window|globalThis|self|top|parent)\\s*\\.\\s*open\\s*\\("),
            pattern("\\b(?:(?:window|document)\\s*\\.\\s*)?location\\b"),
            pattern("\\b(?:navigator\\s*\\.\\s*)?clipboard\\b"),
            pattern("\\b(?:atob|btoa|eval)\\s*\\("),
            pattern("\\bdocument\\s*\\.\\s*write(?:ln)?\\s*\\("),
            pattern("\\bsetAttribute(?:NS)?\\s*\\(\\s*['\"](?:src|srcset|href|xlink:href|action|formaction|poster|ping)['\"]"),
            pattern("\\bexecCommand\\s*\\(\\s*['\"]\\s*(?:copy|cut|paste)\\s*['\"]"),
            pattern("\\b(?:setTimeout|setInterval)\\s*\\(\\s*['\"`]"),
            pattern("\\bDOMParser\\b"),
            pattern("\\b(?:innerHTML|outerHTML|insertAdjacentHTML)\\b"),
            pattern("\\b(?:Worker|SharedWorker)\\b"),
            pattern("\\b(?:navigator\\s*\\.\\s*)?serviceWorker\\b"),
            pattern("\\b(?:mediaDevices|getUserMedia|webkitGetUserMedia|mozGetUserMedia|getDisplayMedia|geolocation)\\b"),
            Pattern.compile("\\b(?:new\\s+)?Function\\s*\\(", Pattern.DOTALL),
            Pattern.compile("\\bString\\s*\\.\\s*from(?:CharCode|CodePoint)\\s*\\(", Pattern.DOTALL),
            URL_LITERAL,
            EXECUTABLE_PROTOCOL_RELATIVE_URL);

    private final ObjectMapper objectMapper;

    public InteractiveArtifactValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(InteractiveArtifactManifest manifest, byte[] htmlBytes) {
        validateManifest(manifest);
        validateHtml(htmlBytes);
    }

    public void validateManifest(InteractiveArtifactManifest manifest) {
        if (manifest == null) throw invalid("manifest is required");
        if (manifest.schemaVersion() != 1) throw invalid("schemaVersion must be 1");
        requireLength(manifest.title(), 1, 80, "title");
        requireLength(manifest.fallback(), 1, 500, "fallback");
        if (!manifest.permissions().isEmpty()) throw invalid("permissions must be empty in V1");
        if (!manifest.network().isEmpty()) throw invalid("network must be empty in V1");
        requireJsonDepth(manifest.initialData(), MAX_INITIAL_DATA_DEPTH, "initialData");
        requireJsonSize(manifest.initialData(), MAX_INITIAL_DATA_BYTES, "initialData");
        validateStateSchema(manifest.stateSchema());
        requireJsonSize(manifest.stateSchema(), MAX_STATE_SCHEMA_BYTES, "stateSchema");
    }

    private void validateHtml(byte[] htmlBytes) {
        if (htmlBytes == null || htmlBytes.length == 0) throw invalid("HTML is required");
        if (htmlBytes.length > MAX_HTML_BYTES) throw invalid("HTML exceeds size limit");
        String html;
        try {
            html = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(htmlBytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("HTML must be valid UTF-8");
        }
        if (html.codePoints().anyMatch(InteractiveArtifactValidator::isForbiddenHtmlControl)) {
            throw forbidden("control character");
        }
        Document document = Jsoup.parse(html);
        scanComments(document);
        for (Element element : document.getAllElements()) {
            String tag = element.normalName();
            if (FORBIDDEN_TAGS.contains(tag)) {
                throw forbidden("active <" + tag + "> element");
            }
            if ("meta".equals(tag)
                    && "refresh".equalsIgnoreCase(element.attr("http-equiv").strip())) {
                throw forbidden("meta refresh");
            }
            if ("input".equals(tag)
                    && "file".equalsIgnoreCase(element.attr("type").strip())) {
                throw forbidden("file input permission entry point");
            }

            for (Attribute attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                if (name.length() > 2 && name.startsWith("on")) {
                    throw forbidden("inline event handler");
                }
                if ("capture".equals(name)) {
                    throw forbidden("capture permission entry point");
                }
                if ("data-sf-url".equals(name)) {
                    validateDataSfUrl(attribute.getValue());
                } else if (containsUrlLiteral(attribute.getValue())) {
                    throw forbidden("URL outside an approved inert data slot");
                }
                if (ACTIVE_REFERENCE_ATTRIBUTES.contains(name)) {
                    validateActiveReference(tag, name, attribute.getValue());
                }
                if ("style".equals(name)) {
                    scanCss(attribute.getValue());
                }
            }

            if ("style".equals(tag)) {
                scanCss(element.data());
            } else if ("script".equals(tag)) {
                scanScriptElement(element);
            }
        }
    }

    private void scanScriptElement(Element script) {
        String source = script.data();
        if ("application/json".equalsIgnoreCase(script.attr("type").strip())) {
            try {
                if (source.isBlank() || objectMapper.readTree(source) == null) {
                    throw forbidden("invalid application/json data block");
                }
            } catch (JsonProcessingException e) {
                throw forbidden("invalid application/json data block");
            }
            return;
        }
        scanExecutable(source, "executable script");
    }

    private static void validateActiveReference(String tag, String attribute, String value) {
        if (("script".equals(tag) && "src".equals(attribute))
                || ("link".equals(tag) && "href".equals(attribute))) {
            throw forbidden("external script or link resource");
        }
        String normalized = value.strip();
        if (("href".equals(attribute) || "xlink:href".equals(attribute))
                && normalized.startsWith("#") && normalized.length() > 1) {
            return;
        }
        throw forbidden("active " + attribute + " reference");
    }

    private static void validateDataSfUrl(String value) {
        if (value.isEmpty()
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_EXTERNAL_URL_BYTES
                || !value.equals(value.strip())
                || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(codePoint ->
                        Character.getType(codePoint) == Character.CONTROL)
                || !hasValidPercentEscapes(value)) {
            throw forbidden("data-sf-url value");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || !isConservativeHttpAuthority(uri.getRawAuthority())
                    || host == null || host.isEmpty()
                    || uri.getUserInfo() != null) {
                throw forbidden("data-sf-url value");
            }
        } catch (URISyntaxException e) {
            throw forbidden("data-sf-url value");
        }
    }

    private static boolean isConservativeHttpAuthority(String authority) {
        if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0
                || authority.charAt(0) == '[' || authority.indexOf('%') >= 0) {
            return false;
        }
        for (int i = 0; i < authority.length(); i++) {
            if (authority.charAt(i) > 0x7f) return false;
        }

        String host = authority;
        int colon = authority.indexOf(':');
        if (colon >= 0) {
            if (colon != authority.lastIndexOf(':') || colon == 0 || colon == authority.length() - 1) {
                return false;
            }
            String port = authority.substring(colon + 1);
            if (!port.chars().allMatch(InteractiveArtifactValidator::isAsciiDigit)) return false;
            host = authority.substring(0, colon);
        }
        return isConservativeAsciiHost(host);
    }

    private static boolean isConservativeAsciiHost(String host) {
        if (host.isEmpty() || host.length() > 253) return false;
        boolean numericHost = host.chars().allMatch(value -> value == '.' || isAsciiDigit(value));
        if (numericHost) return isStandardIpv4(host);

        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || !isAsciiAlphanumeric(label.charAt(0))
                    || !isAsciiAlphanumeric(label.charAt(label.length() - 1))) {
                return false;
            }
            if (!label.chars().allMatch(value -> isAsciiAlphanumeric(value) || value == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStandardIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3
                    || !octet.chars().allMatch(InteractiveArtifactValidator::isAsciiDigit)
                    || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            if (Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    private static boolean isAsciiAlphanumeric(int value) {
        return isAsciiDigit(value)
                || (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z');
    }

    private static boolean isAsciiDigit(int value) {
        return value >= '0' && value <= '9';
    }

    private static boolean hasValidPercentEscapes(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '%') continue;
            if (i + 2 >= value.length()
                    || !isHex(value.charAt(i + 1))
                    || !isHex(value.charAt(i + 2))) {
                return false;
            }
            i += 2;
        }
        return true;
    }

    private static boolean isForbiddenHtmlControl(int codePoint) {
        return Character.getType(codePoint) == Character.CONTROL
                && codePoint != '\t' && codePoint != '\n' && codePoint != '\r';
    }

    private static void scanCss(String css) {
        if (css == null || css.isBlank()) return;
        String canonical = decodeCssEscapes(BLOCK_COMMENT.matcher(css).replaceAll(""));
        if (containsUrlLiteral(canonical)) {
            throw forbidden("CSS URL literal");
        }
        if (CSS_IMPORT.matcher(canonical).find()) {
            throw forbidden("CSS @import");
        }
        var urls = CSS_URL.matcher(canonical);
        while (urls.find()) {
            String target = urls.group(2).strip();
            if (!target.startsWith("#") || target.length() == 1) {
                throw forbidden("CSS url resource");
            }
        }
    }

    private static void scanComments(Node node) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            Node current = pending.pop();
            if (current instanceof Comment comment && containsUrlLiteral(comment.getData())) {
                throw forbidden("URL outside an approved inert data slot");
            }
            for (int i = current.childNodeSize() - 1; i >= 0; i--) {
                pending.push(current.childNode(i));
            }
        }
    }

    private static boolean containsUrlLiteral(String value) {
        return URL_LITERAL.matcher(value).find() || PROTOCOL_RELATIVE_URL.matcher(value).find();
    }

    private static void scanExecutable(String source, String scope) {
        if (source == null || source.isBlank()) return;
        String canonical = normalizeExecutableScript(source);
        for (Pattern pattern : FORBIDDEN_EXECUTABLE_SCRIPT) {
            if (pattern.matcher(canonical).find()) {
                throw forbidden(scope + " capability");
            }
        }
    }

    private static String normalizeExecutableScript(String source) {
        String withoutComments = removeJavaScriptComments(source);
        String decoded = decodeJavaScriptEscapes(withoutComments)
                .replace("?.[", "[")
                .replace("?.", ".");
        var properties = BRACKET_PROPERTY.matcher(decoded);
        StringBuilder canonical = new StringBuilder(decoded.length());
        while (properties.find()) {
            properties.appendReplacement(canonical, Matcher.quoteReplacement("." + properties.group(2)));
        }
        properties.appendTail(canonical);
        return canonical.toString();
    }

    /**
     * Removes actual JavaScript comments without treating // or /* inside quoted strings or
     * template text as comments. Template expressions are scanned as code so a capability
     * split by a line comment inside ${...} cannot bypass canonicalization.
     */
    private static String removeJavaScriptComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        ArrayDeque<Integer> templateExpressionDepths = new ArrayDeque<>();
        JavaScriptLexicalState state = JavaScriptLexicalState.CODE;
        for (int i = 0; i < source.length();) {
            char current = source.charAt(i);
            if (state == JavaScriptLexicalState.SINGLE_QUOTED
                    || state == JavaScriptLexicalState.DOUBLE_QUOTED) {
                result.append(current);
                if (current == '\\' && i + 1 < source.length()) {
                    result.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if ((state == JavaScriptLexicalState.SINGLE_QUOTED && current == '\'')
                        || (state == JavaScriptLexicalState.DOUBLE_QUOTED && current == '"')) {
                    state = JavaScriptLexicalState.CODE;
                }
                i++;
                continue;
            }
            if (state == JavaScriptLexicalState.TEMPLATE_TEXT) {
                result.append(current);
                if (current == '\\' && i + 1 < source.length()) {
                    result.append(source.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (current == '`') {
                    state = JavaScriptLexicalState.CODE;
                    i++;
                    continue;
                }
                if (current == '$' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                    result.append('{');
                    templateExpressionDepths.push(0);
                    state = JavaScriptLexicalState.CODE;
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }

            if (current == '\'' || current == '"') {
                result.append(current);
                state = current == '\''
                        ? JavaScriptLexicalState.SINGLE_QUOTED
                        : JavaScriptLexicalState.DOUBLE_QUOTED;
                i++;
                continue;
            }
            if (current == '`') {
                result.append(current);
                state = JavaScriptLexicalState.TEMPLATE_TEXT;
                i++;
                continue;
            }
            if (current == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                result.append(' ');
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
                continue;
            }
            if (current == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                result.append(' ');
                i += 2;
                while (i < source.length()) {
                    if (source.charAt(i) == '*' && i + 1 < source.length()
                            && source.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    if (source.charAt(i) == '\n' || source.charAt(i) == '\r') {
                        result.append(source.charAt(i));
                    }
                    i++;
                }
                continue;
            }
            if (current == '{' && !templateExpressionDepths.isEmpty()) {
                templateExpressionDepths.push(templateExpressionDepths.pop() + 1);
            } else if (current == '}' && !templateExpressionDepths.isEmpty()) {
                int depth = templateExpressionDepths.pop();
                if (depth == 0) {
                    state = JavaScriptLexicalState.TEMPLATE_TEXT;
                } else {
                    templateExpressionDepths.push(depth - 1);
                }
            }
            result.append(current);
            i++;
        }
        return result.toString();
    }

    private enum JavaScriptLexicalState {
        CODE,
        SINGLE_QUOTED,
        DOUBLE_QUOTED,
        TEMPLATE_TEXT
    }

    private static String decodeJavaScriptEscapes(String source) {
        StringBuilder decoded = new StringBuilder(source.length());
        for (int i = 0; i < source.length();) {
            char current = source.charAt(i);
            if (current != '\\' || i + 1 >= source.length()) {
                decoded.append(current);
                i++;
                continue;
            }
            int escapeStart = i;
            char kind = source.charAt(i + 1);
            int value = -1;
            int end = i + 2;
            if (kind == 'u' && end < source.length() && source.charAt(end) == '{') {
                int close = source.indexOf('}', end + 1);
                if (close > end + 1 && close - end <= 7) {
                    value = parseHex(source, end + 1, close);
                    end = close + 1;
                }
            } else if (kind == 'u' && end + 4 <= source.length()) {
                value = parseHex(source, end, end + 4);
                end += 4;
            } else if (kind == 'x' && end + 2 <= source.length()) {
                value = parseHex(source, end, end + 2);
                end += 2;
            }
            if (value >= 0 && Character.isValidCodePoint(value)) {
                decoded.appendCodePoint(value);
                i = end;
            } else {
                decoded.append(source, escapeStart, Math.min(source.length(), escapeStart + 2));
                i = Math.min(source.length(), escapeStart + 2);
            }
        }
        return decoded.toString();
    }

    private static String decodeCssEscapes(String css) {
        StringBuilder decoded = new StringBuilder(css.length());
        for (int i = 0; i < css.length();) {
            char current = css.charAt(i);
            if (current != '\\' || i + 1 >= css.length()) {
                decoded.append(current);
                i++;
                continue;
            }
            int start = i + 1;
            int end = start;
            while (end < css.length() && end - start < 6 && isHex(css.charAt(end))) end++;
            if (end > start) {
                int value = parseHex(css, start, end);
                if (value >= 0 && Character.isValidCodePoint(value)) decoded.appendCodePoint(value);
                if (end < css.length() && Character.isWhitespace(css.charAt(end))) end++;
                i = end;
            } else {
                char escaped = css.charAt(start);
                if (escaped != '\n' && escaped != '\r' && escaped != '\f') decoded.append(escaped);
                i = start + 1;
            }
        }
        return decoded.toString();
    }

    private static int parseHex(String value, int start, int end) {
        if (start >= end) return -1;
        int result = 0;
        for (int i = start; i < end; i++) {
            int digit = Character.digit(value.charAt(i), 16);
            if (digit < 0) return -1;
            result = result * 16 + digit;
        }
        return result;
    }

    private static boolean isHex(char value) {
        return Character.digit(value, 16) >= 0;
    }

    private static void validateStateSchema(Map<String, Object> stateSchema) {
        SchemaTraversalBudget budget = new SchemaTraversalBudget();
        validateSchemaObject(stateSchema, "stateSchema", 1, budget);
        if (!"object".equals(stateSchema.get("type"))) {
            throw invalid("stateSchema root type must be object");
        }
    }

    private static void validateSchemaObject(
            Map<?, ?> schema, String path, int depth, SchemaTraversalBudget budget) {
        budget.consume(depth);
        Object rawType = schema.get("type");
        if (!(rawType instanceof String type) || !SUPPORTED_SCHEMA_TYPES.contains(type)) {
            throw invalid(path + " type must be one of " + SUPPORTED_SCHEMA_TYPES);
        }
        budget.consume(depth + 1);
        Set<String> allowedKeywords = switch (type) {
            case "object" -> OBJECT_SCHEMA_KEYWORDS;
            case "array" -> ARRAY_SCHEMA_KEYWORDS;
            default -> SCALAR_SCHEMA_KEYWORDS;
        };
        for (Object rawKey : schema.keySet()) {
            if (!(rawKey instanceof String key) || !allowedKeywords.contains(key)) {
                throw invalid(path + " contains unsupported keyword: " + rawKey);
            }
        }
        if ("object".equals(type)) {
            validateObjectSchema(schema, path, depth, budget);
        } else if ("array".equals(type)) {
            validateArraySchema(schema, path, depth, budget);
        }
    }

    private static void validateObjectSchema(
            Map<?, ?> schema, String path, int depth, SchemaTraversalBudget budget) {
        if (schema.containsKey("properties")) {
            Object rawProperties = schema.get("properties");
            if (!(rawProperties instanceof Map<?, ?> properties)) {
                throw invalid(path + ".properties must be an object");
            }
            budget.consume(depth + 1);
            for (Map.Entry<?, ?> property : properties.entrySet()) {
                if (!(property.getKey() instanceof String name)
                        || !(property.getValue() instanceof Map<?, ?> childSchema)) {
                    throw invalid(path + ".properties must map names to schema objects");
                }
                validateSchemaObject(childSchema, path + ".properties." + name, depth + 2, budget);
            }
        }
        if (schema.containsKey("required")) {
            Object rawRequired = schema.get("required");
            if (!(rawRequired instanceof List<?> required)) {
                throw invalid(path + ".required must be an array of strings");
            }
            budget.consume(depth + 1);
            for (Object value : required) {
                budget.consume(depth + 2);
                if (!(value instanceof String)) {
                    throw invalid(path + ".required must be an array of strings");
                }
            }
        }
        if (schema.containsKey("additionalProperties")) {
            Object additional = schema.get("additionalProperties");
            if (additional instanceof Boolean) {
                budget.consume(depth + 1);
                return;
            }
            if (additional instanceof Map<?, ?> childSchema) {
                validateSchemaObject(
                        childSchema, path + ".additionalProperties", depth + 1, budget);
                return;
            }
            throw invalid(path + ".additionalProperties must be a boolean or schema object");
        }
    }

    private static void validateArraySchema(
            Map<?, ?> schema, String path, int depth, SchemaTraversalBudget budget) {
        if (!schema.containsKey("items")) return;
        Object items = schema.get("items");
        if (!(items instanceof Map<?, ?> itemSchema)) {
            throw invalid(path + ".items must be a schema object");
        }
        validateSchemaObject(itemSchema, path + ".items", depth + 1, budget);
    }

    private static final class SchemaTraversalBudget {
        private int remainingNodes = MAX_SCHEMA_NODES;

        private void consume(int depth) {
            if (depth > MAX_SCHEMA_DEPTH) {
                throw invalid("stateSchema exceeds maximum depth");
            }
            if (remainingNodes == 0) {
                throw invalid("stateSchema exceeds maximum node count");
            }
            remainingNodes--;
        }
    }

    private void requireJsonSize(Object value, int maxBytes, String field) {
        try {
            if (objectMapper.writeValueAsBytes(value).length > maxBytes) {
                throw invalid(field + " exceeds size limit");
            }
        } catch (JsonProcessingException e) {
            throw invalid(field + " must be JSON serializable");
        }
    }

    private static void requireJsonDepth(Object root, int maxDepth, String field) {
        ArrayDeque<JsonValueAtDepth> pending = new ArrayDeque<>();
        pending.push(new JsonValueAtDepth(root, 1));
        while (!pending.isEmpty()) {
            JsonValueAtDepth current = pending.pop();
            if (current.depth() > maxDepth) {
                throw invalid(field + " exceeds maximum depth");
            }
            if (current.value() instanceof Map<?, ?> map) {
                for (Object child : map.values()) {
                    pending.push(new JsonValueAtDepth(child, current.depth() + 1));
                }
            } else if (current.value() instanceof Iterable<?> iterable) {
                for (Object child : iterable) {
                    pending.push(new JsonValueAtDepth(child, current.depth() + 1));
                }
            }
        }
    }

    private record JsonValueAtDepth(Object value, int depth) { }

    private static void requireLength(String value, int min, int max, String field) {
        int rawLength = value == null ? 0 : value.length();
        int trimmedLength = value == null ? 0 : value.strip().length();
        if (trimmedLength < min || rawLength > max) {
            throw invalid(field + " length is invalid");
        }
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    private static IllegalArgumentException forbidden(String capability) {
        return invalid("HTML contains forbidden " + capability);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Interactive artifact " + message);
    }
}
