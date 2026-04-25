package com.skillforge.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.model.ToolSchema;
import com.skillforge.core.skill.Tool;
import com.skillforge.core.skill.SkillContext;
import com.skillforge.core.skill.SkillResult;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tool that fetches a URL and returns its content as text.
 * Converts HTML to readable text using Jsoup.
 */
public class WebFetchTool implements Tool {

    private static final int DEFAULT_MAX_LENGTH = 20_000;
    private static final String USER_AGENT = "SkillForge/1.0";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebFetchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        this.objectMapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public String getName() {
        return "WebFetch";
    }

    @Override
    public String getDescription() {
        return "Fetch a URL and return its content as text. "
                + "Converts HTML to readable text by stripping tags. "
                + "Useful for reading web pages, APIs, and documentation.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of(
                "type", "string",
                "description", "The URL to fetch"
        ));
        properties.put("maxLength", Map.of(
                "type", "integer",
                "description", "Maximum characters to return (default 20000)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String url = (String) input.get("url");
            if (url == null || url.isBlank()) {
                return SkillResult.error("url is required");
            }

            int maxLength = DEFAULT_MAX_LENGTH;
            Object maxLengthObj = input.get("maxLength");
            if (maxLengthObj instanceof Number) {
                maxLength = ((Number) maxLengthObj).intValue();
            }

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                int statusCode = response.code();
                String contentType = response.header("Content-Type", "");

                ResponseBody body = response.body();
                if (body == null) {
                    return SkillResult.success(formatHeader(url, statusCode, contentType) + "\n\n[Empty response body]");
                }

                String rawContent = body.string();
                String content;

                if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                    content = htmlToText(rawContent);
                } else if (contentType.contains("json")) {
                    content = prettyPrintJson(rawContent);
                } else {
                    content = rawContent;
                }

                if (content.length() > maxLength) {
                    content = content.substring(0, maxLength) + "\n\n... [truncated at " + maxLength + " chars]";
                }

                return SkillResult.success(formatHeader(url, statusCode, contentType) + "\n\n" + content);
            }
        } catch (Exception e) {
            return SkillResult.error("Failed to fetch URL: " + e.getMessage());
        }
    }

    private String formatHeader(String url, int statusCode, String contentType) {
        return "URL: " + url + "\nStatus: " + statusCode + "\nContent-Type: " + contentType;
    }

    private String htmlToText(String html) {
        Document doc = Jsoup.parse(html);
        doc.select("script, style, nav, footer, header").remove();
        return doc.body() != null ? doc.body().text() : doc.text();
    }

    private String prettyPrintJson(String json) {
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return objectMapper.writeValueAsString(parsed);
        } catch (Exception e) {
            // Not valid JSON, return as-is
            return json;
        }
    }
}
