package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.files.FileAttachment;

import java.util.List;

public record ChatMessage(String participant, String content, long timestampMillis, List<FileAttachment> files) {

    public ChatMessage {
        files = files != null ? files : List.of();
    }

    public static ChatMessage of(String participant, String content) {
        return new ChatMessage(participant, content, System.currentTimeMillis(), List.of());
    }

    public static ChatMessage ofWithFiles(String participant, String content, List<FileAttachment> files) {
        return new ChatMessage(participant, content, System.currentTimeMillis(), files != null ? files : List.of());
    }
}