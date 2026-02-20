package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record ChatResult(String response, List<ChatMessage> toolMessages,
                         List<String> pendingSubConversationIds) {
}
