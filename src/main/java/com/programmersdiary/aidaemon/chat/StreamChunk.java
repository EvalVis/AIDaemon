package com.programmersdiary.aidaemon.chat;

public record StreamChunk(String type, String content) {
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_ANSWER = "answer";
    public static final String TYPE_TOOL = "tool";
    /** Emitted before a tool runs when manual-approve is enabled. Content is JSON: {approvalId, toolName, toolInput}. */
    public static final String TYPE_TOOL_PENDING = "tool_pending";
}
