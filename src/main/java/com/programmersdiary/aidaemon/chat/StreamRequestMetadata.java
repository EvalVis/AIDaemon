package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record StreamRequestMetadata(List<ChatMessage> messages, String conversationId, String botName, int conversationLimit) {

    public StreamRequestMetadata {
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}
