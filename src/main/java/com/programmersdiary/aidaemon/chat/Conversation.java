package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record Conversation(String id, String name, String providerId, List<ChatMessage> messages,
                           String parentConversationId) {
}
