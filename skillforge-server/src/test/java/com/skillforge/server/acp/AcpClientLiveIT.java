package com.skillforge.server.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in LIVE integration test that spawns the REAL cc ACP adapter and runs the
 * spike "pong" handshake end-to-end against {@link AcpClient} +
 * {@link ProcessAcpTransport}.
 *
 * <p>DISABLED by default — gated behind the {@code ACP_LIVE_IT} env var so a
 * plain {@code mvn test} never pulls {@code npx} / a real cc subprocess into CI
 * (same spirit as the Docker-gated ITs). It also consumes cc tokens.
 *
 * <h3>How to run</h3>
 * <pre>
 *   # Requires: node/npx on PATH, the cc ACP adapter resolvable, and cc logged in.
 *   ACP_LIVE_IT=1 mvn -pl skillforge-server test \
 *       -Dtest=AcpClientLiveIT -Dsurefire.failIfNoSpecifiedTests=false
 *
 *   # Optional override of the adapter package (defaults to the renamed one):
 *   ACP_LIVE_IT=1 ACP_ADAPTER_PACKAGE=@zed-industries/claude-code-acp mvn ...
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "ACP_LIVE_IT", matches = "1|true")
class AcpClientLiveIT {

    @Test
    @DisplayName("LIVE: real adapter handshake → session/new → prompt streams a text chunk")
    void liveHandshakeAndPong() throws Exception {
        // Test-only plain ObjectMapper: ACP payloads have no time-typed fields, so the
        // JavaTimeModule footgun (#1) does not apply. Production injects the Spring bean.
        ObjectMapper mapper = new ObjectMapper();
        String cwd = System.getProperty("java.io.tmpdir");
        String pkg = System.getenv().getOrDefault(
                "ACP_ADAPTER_PACKAGE", ProcessAcpTransport.DEFAULT_ADAPTER_PACKAGE);

        ProcessAcpTransport transport =
                ProcessAcpTransport.forAdapterPackage(pkg, cwd, Map.of());
        AcpClient client = new AcpClient(transport, mapper, new CcAcpUpdateTranslator());

        List<AcpUpdate> updates = new ArrayList<>();
        AtomicReference<String> textBuf = new AtomicReference<>("");
        client.setUpdateListener(u -> {
            updates.add(u.update());
            if (u.update() instanceof AcpUpdate.TextChunk tc) {
                textBuf.updateAndGet(s -> s + tc.text());
            }
        });

        try {
            client.start();

            JsonNode init = client.initialize().get(60, TimeUnit.SECONDS);
            assertThat(init.get("protocolVersion").asInt()).isEqualTo(1);

            JsonNode session = client.newSession(cwd, List.of()).get(60, TimeUnit.SECONDS);
            String sessionId = session.get("sessionId").asText();
            assertThat(sessionId).isNotBlank();

            CompletableFuture<JsonNode> promptFut = client.prompt(sessionId, List.of(
                    mapper.createObjectNode().put("type", "text")
                            .put("text", "Reply with exactly the word: pong")));
            JsonNode result = promptFut.get(90, TimeUnit.SECONDS);

            assertThat(result.has("stopReason")).isTrue();
            assertThat(updates).isNotEmpty();
            assertThat(textBuf.get().toLowerCase()).contains("pong");
        } finally {
            client.close();
        }
    }
}
