package com.skillforge.server.service;

import com.skillforge.core.model.ContentBlock;
import com.skillforge.core.model.Message;
import com.skillforge.server.repository.ChatAttachmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps an interactive artifact's source assistant sequence aligned with row persistence. */
@Service
public class PersonalAppSourceLinkReconciler {

    private final ChatAttachmentRepository attachmentRepository;

    public PersonalAppSourceLinkReconciler(ChatAttachmentRepository attachmentRepository) {
        this.attachmentRepository = attachmentRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearForRewrite(String sessionId) {
        attachmentRepository.clearPersonalAppSourceMessages(sessionId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileAppended(String sessionId, long firstSeq, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        Map<String, Long> latestSeqByArtifact = new LinkedHashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (message == null || message.getRole() != Message.Role.ASSISTANT
                    || !(message.getContent() instanceof List<?> blocks)) {
                continue;
            }
            for (Object block : blocks) {
                String artifactId = interactiveArtifactId(block);
                if (artifactId != null && !artifactId.isBlank()) {
                    latestSeqByArtifact.put(artifactId, firstSeq + index);
                }
            }
        }
        latestSeqByArtifact.forEach((artifactId, sourceSeq) ->
                attachmentRepository.bindPersonalAppSourceMessage(
                        sessionId, artifactId, sourceSeq));
    }

    private static String interactiveArtifactId(Object block) {
        if (block instanceof ContentBlock contentBlock) {
            return "interactive_artifact_ref".equals(contentBlock.getType())
                    ? contentBlock.getAttachmentId() : null;
        }
        if (!(block instanceof Map<?, ?> map)
                || !"interactive_artifact_ref".equals(map.get("type"))) {
            return null;
        }
        Object id = map.get("attachment_id");
        if (id == null) id = map.get("attachmentId");
        return id instanceof String value ? value : null;
    }
}
