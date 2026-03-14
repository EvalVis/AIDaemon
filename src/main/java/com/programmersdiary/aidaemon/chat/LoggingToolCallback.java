package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class LoggingToolCallback implements ToolCallback {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback delegate;
    private final List<ChatMessage> toolLog;
    private final String serverName;
    private final Consumer<StreamChunk> onToolChunk;
    private final ToolApprovalService approvalService;

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog) {
        this(delegate, toolLog, null, null, null);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName) {
        this(delegate, toolLog, serverName, null, null);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName,
                               Consumer<StreamChunk> onToolChunk) {
        this(delegate, toolLog, serverName, onToolChunk, null);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName,
                               Consumer<StreamChunk> onToolChunk, ToolApprovalService approvalService) {
        this.delegate = delegate;
        this.toolLog = toolLog;
        this.serverName = serverName;
        this.onToolChunk = onToolChunk;
        this.approvalService = approvalService;
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
        if (approvalService != null) {
            var approved = requestApproval(toolInput);
            if (!approved) return buildRejectionResult(toolInput);
        }
        var result = delegate.call(toolInput);
        log(toolInput, result);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (approvalService != null) {
            var approved = requestApproval(toolInput);
            if (!approved) return buildRejectionResult(toolInput);
        }
        var result = delegate.call(toolInput, toolContext);
        log(toolInput, result);
        return result;
    }

    private boolean requestApproval(String toolInput) {
        var approvalId = UUID.randomUUID().toString();
        var toolName = delegate.getToolDefinition().name();
        var label = serverName != null ? serverName + " > " + toolName : toolName;
        var pendingContent = buildPendingJson(approvalId, label, toolInput);
        // Register the future BEFORE emitting the chunk so the consumer can approve/reject immediately
        var future = approvalService.requestApproval(approvalId);
        if (onToolChunk != null) {
            onToolChunk.accept(new StreamChunk(StreamChunk.TYPE_TOOL_PENDING, pendingContent));
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }

    private String buildRejectionResult(String toolInput) {
        var toolName = delegate.getToolDefinition().name();
        var label = serverName != null ? serverName + " > " + toolName : toolName;
        var content = "[" + label + "]\nInput: " + toolInput + "\nOutput: Tool call rejected by user.";
        toolLog.add(ChatMessage.of("tool", content));
        if (onToolChunk != null) {
            onToolChunk.accept(new StreamChunk(StreamChunk.TYPE_TOOL, content));
        }
        return "Tool call was rejected by the user.";
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

    private static String buildPendingJson(String approvalId, String toolName, String toolInput) {
        try {
            return JSON.writeValueAsString(Map.of(
                    "approvalId", approvalId,
                    "toolName", toolName,
                    "toolInput", toolInput));
        } catch (Exception e) {
            return "{}";
        }
    }
}
