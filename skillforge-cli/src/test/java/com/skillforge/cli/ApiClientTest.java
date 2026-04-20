package com.skillforge.cli;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiClientTest {

    private MockWebServer server;
    private ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        String base = server.url("").toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        api = new ApiClient(base, 1L, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void listAgentsSendsGet() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":1,\"name\":\"A\"}]"));
        JsonNode node = api.listAgents();
        assertThat(node.isArray()).isTrue();
        assertThat(node.get(0).get("id").asLong()).isEqualTo(1L);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).isEqualTo("/api/agents");
    }

    @Test
    void getAgentUsesPathId() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":42}"));
        api.getAgent(42);
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/agents/42");
    }

    @Test
    void importAgentPostsYaml() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":7,\"name\":\"X\"}"));
        JsonNode resp = api.importAgentYaml("name: X\nskills:\n  - Bash\n");
        assertThat(resp.get("id").asInt()).isEqualTo(7);
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/agents/import");
        assertThat(req.getHeader("Content-Type")).startsWith("application/yaml");
        assertThat(req.getBody().readUtf8()).contains("name: X");
    }

    @Test
    void exportAgentReturnsYamlText() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/yaml")
                .setBody("name: X\nskills:\n  - Bash\n"));
        String yaml = api.exportAgentYaml(5);
        assertThat(yaml).contains("name: X");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/agents/5/export");
    }

    @Test
    void createSessionSendsUserAndAgent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"abc\"}"));
        JsonNode r = api.createSession(3);
        assertThat(r.get("id").asText()).isEqualTo("abc");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/chat/sessions");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"userId\"").contains("1")
                .contains("\"agentId\"").contains("3");
    }

    @Test
    void sendMessagePostsToSession() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"accepted\"}"));
        api.sendMessage("s1", "hello");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/chat/s1");
        assertThat(req.getBody().readUtf8()).contains("\"message\"").contains("hello");
    }

    @Test
    void getSessionAppendsUserId() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"s1\",\"runtimeStatus\":\"idle\"}"));
        api.getSession("s1");
        assertThat(server.takeRequest().getPath()).isEqualTo("/api/chat/sessions/s1?userId=1");
    }

    @Test
    void cancelPostsWithUserId() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"cancelling\"}"));
        api.cancelSession("s1");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/api/chat/s1/cancel?userId=1");
    }

    @Test
    void compactPostsBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":10,\"level\":\"full\"}"));
        api.compact("s1", "full", "test");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/chat/sessions/s1/compact?userId=1");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"level\"").contains("full")
                .contains("\"reason\"").contains("test");
    }

    @Test
    void setSessionModeUsesPatchAndCarriesMode() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"s1\",\"mode\":\"ask\"}"));

        JsonNode resp = api.setSessionMode("s1", "ask");

        assertThat(resp.get("mode").asText()).isEqualTo("ask");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("PATCH");
        assertThat(req.getPath()).isEqualTo("/api/chat/sessions/s1/mode?userId=1");
        assertThat(req.getBody().readUtf8()).contains("\"mode\"").contains("ask");
    }

    @Test
    void compactWithoutReasonOmitsReasonField() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":11,\"level\":\"light\"}"));

        api.compact("s2", "light", null);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/chat/sessions/s2/compact?userId=1");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"level\"").contains("light");
        assertThat(body).doesNotContain("\"reason\"");
    }

    @Test
    void nonSuccessThrowsApiException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ApiClient.ApiException.class, () -> api.listAgents()).code).isEqualTo(500);
    }
}
