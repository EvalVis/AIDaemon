package com.programmersdiary.aidaemon.chat;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final List<ChatMessage> toolLog;

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog) {
        this.delegate = delegate;
        this.toolLog = toolLog;
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
        var name = delegate.getToolDefinition().name();
        var content = "[" + name + "]\nInput: " + input + "\nOutput: " + output;
        toolLog.add(new ChatMessage("tool", content));
    }
}
