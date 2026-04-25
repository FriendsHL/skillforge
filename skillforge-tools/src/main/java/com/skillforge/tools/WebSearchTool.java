package com.skillforge.tools;

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
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tool that searches the web using DuckDuckGo and returns results.
 */
public class WebSearchTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final String USER_AGENT = "SkillForge/1.0";

    private final OkHttpClient httpClient;

    public WebSearchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Search the web and return results. Returns titles, URLs, and snippets.";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolSchema getToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Search query"
        ));
        properties.put("maxResults", Map.of(
                "type", "integer",
                "description", "Maximum number of results to return (default 5)"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));

        return new ToolSchema(getName(), getDescription(), schema);
    }

    @Override
    public SkillResult execute(Map<String, Object> input, SkillContext context) {
        try {
            String query = (String) input.get("query");
            if (query == null || query.isBlank()) {
                return SkillResult.error("query is required");
            }

            int maxResults = DEFAULT_MAX_RESULTS;
            Object maxResultsObj = input.get("maxResults");
            if (maxResultsObj instanceof Number) {
                maxResults = ((Number) maxResultsObj).intValue();
            }

            String searchUrl = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);

            Request request = new Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (body == null) {
                    return SkillResult.error("Empty response from search engine");
                }

                String html = body.string();
                Document doc = Jsoup.parse(html);

                List<SearchResult> results = new ArrayList<>();
                Elements resultElements = doc.select(".result__body");

                for (Element element : resultElements) {
                    if (results.size() >= maxResults) {
                        break;
                    }

                    Element titleLink = element.selectFirst(".result__a");
                    Element snippet = element.selectFirst(".result__snippet");
                    Element urlElement = element.selectFirst(".result__url");

                    if (titleLink != null) {
                        String title = titleLink.text();
                        String href = titleLink.attr("href");
                        String snippetText = snippet != null ? snippet.text() : "";
                        String displayUrl = urlElement != null ? urlElement.text().trim() : href;

                        // DuckDuckGo HTML wraps URLs in a redirect; extract actual URL if possible
                        if (href.contains("uddg=")) {
                            try {
                                String decoded = java.net.URLDecoder.decode(
                                        href.substring(href.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                                // Remove any trailing query params from the redirect wrapper
                                int ampIdx = decoded.indexOf('&');
                                if (ampIdx > 0) {
                                    decoded = decoded.substring(0, ampIdx);
                                }
                                href = decoded;
                            } catch (Exception ignored) {
                                // Keep original href
                            }
                        }

                        results.add(new SearchResult(title, href, snippetText, displayUrl));
                    }
                }

                if (results.isEmpty()) {
                    return SkillResult.success("No search results found for: \"" + query + "\"");
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Web search results for: \"").append(query).append("\"\n\n");

                for (int i = 0; i < results.size(); i++) {
                    SearchResult r = results.get(i);
                    sb.append(i + 1).append(". [").append(r.title).append("](").append(r.url).append(")\n");
                    if (!r.snippet.isEmpty()) {
                        sb.append("   ").append(r.snippet).append("\n");
                    }
                    sb.append("\n");
                }

                return SkillResult.success(sb.toString().trim());
            }
        } catch (Exception e) {
            return SkillResult.error("Web search failed: " + e.getMessage());
        }
    }

    private static class SearchResult {
        final String title;
        final String url;
        final String snippet;
        final String displayUrl;

        SearchResult(String title, String url, String snippet, String displayUrl) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
            this.displayUrl = displayUrl;
        }
    }
}
