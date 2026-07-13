package com.skillforge.server.mobile;

import com.skillforge.core.engine.confirm.PendingConfirmation;

public record MobilePendingConfirmationResponse(
        String confirmationId,
        String title,
        String description,
        String installTool,
        String installTarget,
        String commandPreview) {

    static MobilePendingConfirmationResponse from(PendingConfirmation pending) {
        String target = pending.installTarget() != null && !pending.installTarget().isBlank()
                ? pending.installTarget()
                : null;
        String tool = pending.installTool() != null && !pending.installTool().isBlank()
                ? pending.installTool()
                : null;
        String subject = java.util.stream.Stream.of(tool, target)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "));
        return new MobilePendingConfirmationResponse(
                pending.confirmationId(),
                subject.isBlank() ? "需要确认" : "确认执行 " + subject,
                pending.commandPreview(),
                tool,
                target,
                pending.commandPreview());
    }
}
