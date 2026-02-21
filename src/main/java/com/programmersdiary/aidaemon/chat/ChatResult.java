package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record ChatResult(String response, List<ChatMessage> toolMessages,
                         List<String> pendingSubConversationIds, List<StreamChunk> orderedParts) {

    public ChatResult(String response, List<ChatMessage> toolMessages, List<String> pendingSubConversationIds) {
        this(response, toolMessages, pendingSubConversationIds, null);
    }
}
