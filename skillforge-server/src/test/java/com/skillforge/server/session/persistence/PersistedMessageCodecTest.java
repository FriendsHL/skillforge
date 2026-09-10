package com.skillforge.server.session.persistence;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PersistedMessageCodec")
class PersistedMessageCodecTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    JacksonAutoConfiguration.class))
            .withUserConfiguration(CodecConfiguration.class);

    @Test
    @DisplayName("row encode-decode-encode is byte stable for tool blocks, reasoning, metadata and Unicode")
    void encodeRow_roundTrip_isByteStable() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            PersistedMessageCodec codec = context.getBean(PersistedMessageCodec.class);

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("path", "/tmp/精确-🚀.txt");
            input.put("line", 17);
            Message message = new Message();
            message.setRole(Message.Role.ASSISTANT);
            message.setContent(List.of(
                    ContentBlock.text("先定位🧩"),
                    ContentBlock.toolUse("toolu_1", "FileRead", input)));
            message.setReasoningContent("reasoning-𐍈");

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("checkpointId", "cp-1");
            metadata.put("createdAt", Instant.parse("2026-09-02T03:04:05Z"));
            PersistedMessageCodec.PersistedMessage persisted =
                    new PersistedMessageCodec.PersistedMessage(
                            message, "NORMAL", "normal", null, metadata);

            PersistedMessageCodec.EncodedRow first = codec.encodeRow(persisted);
            PersistedMessageCodec.PersistedMessage decoded = codec.decodeRow(first);
            PersistedMessageCodec.EncodedRow second = codec.encodeRow(decoded);

            assertThat(second).isEqualTo(first);
            assertThat(first.reasoningContent()).isEqualTo("reasoning-𐍈");
            assertThat(first.metadataJson()).contains("2026-09-02T03:04:05Z");
        });
    }

    @Test
    @DisplayName("full inbox message JSON roundtrip preserves exact Spring Jackson bytes")
    void writeReadMessage_roundTrip_isByteStable() {
        runner.run(context -> {
            PersistedMessageCodec codec = context.getBean(PersistedMessageCodec.class);
            Message message = Message.toolResult(
                    "toolu_error", "{\"b\":2,\"a\":1}\n🌍", true, "EXECUTION");

            String first = codec.writeMessage(message);
            String second = codec.writeMessage(codec.readMessage(first));

            assertThat(second).isEqualTo(first);
        });
    }

    @Test
    @DisplayName("null optional values remain null rather than being fabricated")
    void encodeRow_nullOptionalValues_remainNull() {
        runner.run(context -> {
            PersistedMessageCodec codec = context.getBean(PersistedMessageCodec.class);
            Message message = Message.user("hello");
            PersistedMessageCodec.EncodedRow encoded = codec.encodeRow(
                    new PersistedMessageCodec.PersistedMessage(
                            message, "NORMAL", "normal", null, Map.of()));

            assertThat(encoded.controlId()).isNull();
            assertThat(encoded.reasoningContent()).isNull();
            assertThat(codec.decodeRow(encoded).message().getReasoningContent()).isNull();
        });
    }

    @Test
    @DisplayName("malformed persisted JSON fails closed without echoing transcript content")
    void malformedJson_failsClosed_withoutPayloadInError() {
        runner.run(context -> {
            PersistedMessageCodec codec = context.getBean(PersistedMessageCodec.class);
            String secretPayload = "{malformed-secret-123";

            assertThatThrownBy(() -> codec.readMessage(secretPayload))
                    .isInstanceOf(PersistedMessageCodec.CodecException.class)
                    .hasMessage("Failed to decode persisted message JSON")
                    .hasMessageNotContaining("malformed-secret-123");
        });
    }

    @Test
    @DisplayName("syntactically valid null or role-less inbox messages fail closed")
    void invalidMessageShape_failsClosed() {
        runner.run(context -> {
            PersistedMessageCodec codec = context.getBean(PersistedMessageCodec.class);

            assertThatThrownBy(() -> codec.readMessage("null"))
                    .isInstanceOf(PersistedMessageCodec.CodecException.class)
                    .hasMessage("Persisted message JSON must contain a role");
            assertThatThrownBy(() -> codec.readMessage("{\"content\":\"missing role\"}"))
                    .isInstanceOf(PersistedMessageCodec.CodecException.class)
                    .hasMessage("Persisted message JSON must contain a role");

            Message roleLess = new Message();
            roleLess.setContent("not durable");
            assertThatThrownBy(() -> codec.writeMessage(roleLess))
                    .isInstanceOf(PersistedMessageCodec.CodecException.class)
                    .hasMessage("Persisted message role is required");
        });
    }

    @Test
    @DisplayName("durable row carrier preserves answeredAt and traceId without changing message JSON")
    void encodeRow_answeredAtAndTraceId_roundTripExactly() {
        runner.run(context -> {
            PersistedMessageCodec codec = context.getBean(PersistedMessageCodec.class);
            Instant answeredAt = Instant.parse("2026-09-02T05:06:07.123456Z");
            Message message = Message.assistant("已处理🧩");
            message.setReasoningContent("reasoning-stays-exact");
            PersistedMessageCodec.PersistedMessage persisted =
                    new PersistedMessageCodec.PersistedMessage(
                            message,
                            "SYSTEM_EVENT",
                            "confirmation",
                            "confirmation-1",
                            answeredAt,
                            Map.of("state", "answered"),
                            "trace-123");

            PersistedMessageCodec.EncodedRow encoded = codec.encodeRow(persisted);
            PersistedMessageCodec.PersistedMessage decoded = codec.decodeRow(encoded);
            PersistedMessageCodec.EncodedRow reencoded = codec.encodeRow(decoded);

            assertThat(encoded.answeredAt()).isEqualTo(answeredAt);
            assertThat(encoded.traceId()).isEqualTo("trace-123");
            assertThat(decoded.answeredAt()).isEqualTo(answeredAt);
            assertThat(decoded.traceId()).isEqualTo("trace-123");
            assertThat(reencoded).isEqualTo(encoded);
            assertThat(codec.writeMessage(decoded.message())).isEqualTo(codec.writeMessage(message));
        });
    }

    @Test
    @DisplayName("legacy carrier constructors default answeredAt and traceId to null")
    void persistedMessage_legacyConstructor_defaultsNewCarrierFieldsToNull() {
        PersistedMessageCodec.PersistedMessage persisted =
                new PersistedMessageCodec.PersistedMessage(
                        Message.user("legacy"), "NORMAL", "normal", null, Map.of());
        PersistedMessageCodec.EncodedRow row = new PersistedMessageCodec.EncodedRow(
                "user", "\"legacy\"", null, "NORMAL", "normal", null, "{}");

        assertThat(persisted.answeredAt()).isNull();
        assertThat(persisted.traceId()).isNull();
        assertThat(row.answeredAt()).isNull();
        assertThat(row.traceId()).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    static class CodecConfiguration {
        @Bean
        PersistedMessageCodec persistedMessageCodec(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new PersistedMessageCodec(objectMapper);
        }
    }
}
