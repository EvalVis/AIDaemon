package com.programmersdiary.aidaemon.chat;

import java.util.List;

public record ChatRequest(List<ChatMessage> messages) {
}
