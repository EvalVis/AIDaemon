package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.files.FileAttachment;

import java.util.List;

public record ChatMessage(String role, String content, long timestampMillis, List<FileAttachment> files) {

    public ChatMessage {
        files = files != null ? files : List.of();
    }

    public static ChatMessage of(String role, String content) {
        return new ChatMessage(role, content, System.currentTimeMillis(), List.of());
    }

    public static ChatMessage ofWithFiles(String role, String content, List<FileAttachment> files) {
        return new ChatMessage(role, content, System.currentTimeMillis(), files != null ? files : List.of());
    }
}