package com.programmersdiary.aidaemon.chat;

public record ChatMessage(String role, String content, long timestampMillis) {

    public static ChatMessage of(String role, String content) {
        return new ChatMessage(role, content, System.currentTimeMillis());
    }
}
