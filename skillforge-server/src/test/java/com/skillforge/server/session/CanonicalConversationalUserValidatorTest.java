package com.skillforge.server.session;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class CanonicalConversationalUserValidatorTest {

    @Test
    void safeAttachmentReferenceBlocksAreConversationalUserContent() {
        Message message = new Message();
        message.setRole(Message.Role.USER);
        message.setContent(List.of(
                ContentBlock.text("inspect this"),
                ContentBlock.imageRef("attachment-1", "image/png", "image.png")));

        assertThatCode(() -> CanonicalConversationalUserValidator.requireValid(message))
                .doesNotThrowAnyException();
    }

    @Test
    void toolBlocksAndReasoningAreRejected() {
        Message toolUse = new Message();
        toolUse.setRole(Message.Role.USER);
        toolUse.setContent(List.of(ContentBlock.toolUse(
                "tool-use-1", "MutatingTool", Map.of("value", "secret"))));
        Message toolResult = Message.toolResult("tool-use-1", "secret", false);
        Message reasoning = Message.user("ordinary user text");
        reasoning.setReasoningContent("private reasoning");

        assertThatCode(() -> CanonicalConversationalUserValidator.requireValid(toolUse))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> CanonicalConversationalUserValidator.requireValid(toolResult))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> CanonicalConversationalUserValidator.requireValid(reasoning))
                .isInstanceOf(IllegalStateException.class);
    }
}
