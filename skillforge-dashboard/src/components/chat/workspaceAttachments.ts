import type { ChatAttachmentRef, ChatMessage } from '../ChatWindow';

export interface WorkspaceAttachment extends ChatAttachmentRef {
  origin: 'source' | 'generated';
}

export function collectWorkspaceAttachments(
  messages: ChatMessage[],
): WorkspaceAttachment[] {
  const attachments = new Map<string, WorkspaceAttachment>();

  messages.forEach((message) => {
    if (message.role !== 'user' && message.role !== 'assistant') return;
    for (const attachment of message.attachments ?? []) {
      if (attachments.has(attachment.attachmentId)) continue;
      attachments.set(attachment.attachmentId, {
        ...attachment,
        origin: message.role === 'assistant' ? 'generated' : 'source',
      });
    }
  });

  return [...attachments.values()];
}
