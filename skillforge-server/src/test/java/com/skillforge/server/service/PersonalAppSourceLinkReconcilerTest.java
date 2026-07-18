package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PersonalAppSourceLinkReconcilerTest {

    private ChatAttachmentRepository attachmentRepository;
    private PersonalAppSourceLinkReconciler reconciler;

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(ChatAttachmentRepository.class);
        reconciler = new PersonalAppSourceLinkReconciler(attachmentRepository);
    }

    @Test
    void assistantInteractiveRefsBindToLatestPersistedSequence() {
        Message typed = assistant(List.of(
                ContentBlock.interactiveArtifactRef("artifact-a", "a.html", "A", 1),
                ContentBlock.imageRef("image-ignored", "image/png", "x.png")));
        Message user = Message.user("ignored");
        user.setContent(List.of(Map.of(
                "type", "interactive_artifact_ref",
                "attachment_id", "artifact-user-ignored")));
        Message maps = assistant(List.of(
                Map.of("type", "interactive_artifact_ref", "attachment_id", "artifact-b"),
                Map.of("type", "interactive_artifact_ref", "attachmentId", "artifact-a"),
                Map.of("type", "pdf_ref", "attachment_id", "pdf-ignored")));

        reconciler.reconcileAppended("session-1", 40L, List.of(typed, user, maps));

        var ordered = inOrder(attachmentRepository);
        ordered.verify(attachmentRepository)
                .bindPersonalAppSourceMessage("session-1", "artifact-a", 42L);
        ordered.verify(attachmentRepository)
                .bindPersonalAppSourceMessage("session-1", "artifact-b", 42L);
        verifyNoMoreInteractions(attachmentRepository);
    }

    @Test
    void emptyOrNonBlockContentDoesNotTouchAttachments() {
        reconciler.reconcileAppended("session-1", 0L,
                List.of(Message.assistant("plain text"), Message.user("user")));
        reconciler.reconcileAppended("session-1", 0L, List.of());

        verifyNoMoreInteractions(attachmentRepository);
    }

    @Test
    void rewriteClearDelegatesToScopedRepositoryMutation() {
        reconciler.clearForRewrite("session-1");

        verify(attachmentRepository).clearPersonalAppSourceMessages("session-1");
        verifyNoMoreInteractions(attachmentRepository);
    }

    private static Message assistant(List<?> blocks) {
        Message message = new Message();
        message.setRole(Message.Role.ASSISTANT);
        message.setContent(blocks);
        return message;
    }
}
