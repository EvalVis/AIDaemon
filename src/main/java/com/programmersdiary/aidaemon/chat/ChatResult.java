package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record ChatResult(String response, List<ChatMessage> toolMessages,
                         List<String> pendingSubConversationIds, List<StreamChunk> orderedParts, String reasoning) {

    public ChatResult(String response, List<ChatMessage> toolMessages, List<String> pendingSubConversationIds) {
        this(response, toolMessages, pendingSubConversationIds, null, null);
    }

    public ChatResult(String response, List<ChatMessage> toolMessages, List<String> pendingSubConversationIds, List<StreamChunk> orderedParts) {
        this(response, toolMessages, pendingSubConversationIds, orderedParts, null);
    }
}
