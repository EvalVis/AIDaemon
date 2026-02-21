package com.programmersdiary.aidaemon.chat;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.function.Consumer;

public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final List<ChatMessage> toolLog;
    private final String serverName;
    private final Consumer<StreamChunk> onToolChunk;

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog) {
        this(delegate, toolLog, null, null);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName) {
        this(delegate, toolLog, serverName, null);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName,
                               Consumer<StreamChunk> onToolChunk) {
        this.delegate = delegate;
        this.toolLog = toolLog;
        this.serverName = serverName;
        this.onToolChunk = onToolChunk;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        var def = delegate.getToolDefinition();
        if (serverName == null || serverName.isBlank()) {
            return def;
        }
        var prefixed = serverName + "." + def.name();
        return ToolDefinition.builder()
                .name(sanitizeToolName(prefixed))
                .description(def.description())
                .inputSchema(def.inputSchema())
                .build();
    }

    private static String sanitizeToolName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
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
        toolLog.add(ChatMessage.of("tool", content));
        if (onToolChunk != null) {
            onToolChunk.accept(new StreamChunk(StreamChunk.TYPE_TOOL, content));
        }
    }
}
