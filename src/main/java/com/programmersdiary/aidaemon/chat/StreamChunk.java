package com.programmersdiary.aidaemon.chat;

public record StreamChunk(String type, String content) {
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_ANSWER = "answer";
}
