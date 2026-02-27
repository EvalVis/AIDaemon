package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record Conversation(String id,
                           String name,
                           String providerId,
                           String botName,
                           List<ChatMessage> messages,
                           String parentConversationId,
                           Long createdAtMillis) {

    public Conversation(String id, String name, String providerId, List<ChatMessage> messages, String parentConversationId) {
        this(id, name, providerId, null, messages, parentConversationId, null);
    }
}

