package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record Conversation(String id,
                           String name,
                           String providerId,
                           String botName,
                           List<ChatMessage> messages,
                           String parentConversationId,
                           Long createdAtMillis,
                           String participant1,
                           String participant2) {

    public Conversation(String id, String name, String providerId, List<ChatMessage> messages, String parentConversationId) {
        this(id, name, providerId, null, messages, parentConversationId, null, null, null);
    }

    public Conversation(String id, String name, String providerId, String botName, List<ChatMessage> messages,
                        String parentConversationId, Long createdAtMillis) {
        this(id, name, providerId, botName, messages, parentConversationId, createdAtMillis, null, null);
    }

    public static String canonicalId(String a, String b) {
        if (a == null || b == null) return null;
        return a.compareTo(b) <= 0 ? a + "_" + b : b + "_" + a;
    }

    public boolean isDirect() {
        return participant1 != null && participant2 != null;
    }
}

