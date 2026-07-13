package com.skillforge.server.mobile;

import java.util.List;

public record MobileSendMessageRequest(String message, Long userId, List<String> attachmentIds) {
}
