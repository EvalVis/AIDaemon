package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record Conversation(String id, String providerId, List<ChatMessage> messages) {
}
