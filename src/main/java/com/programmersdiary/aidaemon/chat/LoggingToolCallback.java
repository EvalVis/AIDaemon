package com.programmersdiary.aidaemon.chat;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final List<ChatMessage> toolLog;
    private final String serverName;

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog) {
        this(delegate, toolLog, null);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName) {
        this.delegate = delegate;
        this.toolLog = toolLog;
        this.serverName = serverName;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        var result = delegate.call(toolInput);
        log(toolInput, result);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        var result = delegate.call(toolInput, toolContext);
        log(toolInput, result);
        return result;
    }

    private void log(String input, String output) {
        var toolName = delegate.getToolDefinition().name();
        var label = serverName != null ? serverName + " > " + toolName : toolName;
        var content = "[" + label + "]\nInput: " + input + "\nOutput: " + output;
        toolLog.add(new ChatMessage("tool", content));
    }
}
