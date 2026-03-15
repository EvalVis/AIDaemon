package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class LoggingToolCallback implements ToolCallback {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallback delegate;
    private final List<ChatMessage> toolLog;
    private final String serverName;
    private final Consumer<StreamChunk> onToolChunk;
    private final ToolApprovalService approvalService;
    /** Seconds to wait for tool execution after approval. 0 means no limit. */
    private final int toolExecutionTimeoutSeconds;

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog) {
        this(delegate, toolLog, null, null, null, 0);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName) {
        this(delegate, toolLog, serverName, null, null, 0);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName,
                               Consumer<StreamChunk> onToolChunk) {
        this(delegate, toolLog, serverName, onToolChunk, null, 0);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName,
                               Consumer<StreamChunk> onToolChunk, ToolApprovalService approvalService) {
        this(delegate, toolLog, serverName, onToolChunk, approvalService, 0);
    }

    public LoggingToolCallback(ToolCallback delegate, List<ChatMessage> toolLog, String serverName,
                               Consumer<StreamChunk> onToolChunk, ToolApprovalService approvalService,
                               int toolExecutionTimeoutSeconds) {
        this.delegate = delegate;
        this.toolLog = toolLog;
        this.serverName = serverName;
        this.onToolChunk = onToolChunk;
        this.approvalService = approvalService;
        this.toolExecutionTimeoutSeconds = toolExecutionTimeoutSeconds;
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
            var decision = requestApproval(toolInput);
            if (!decision.approved()) return buildRejectionResult(toolInput, decision.note());
            var result = executeWithTimeout(() -> delegate.call(toolInput), toolInput);
            var resultWithNote = appendNote(result, decision.note());
            log(toolInput, resultWithNote);
            return resultWithNote;
        }
        var result = executeWithTimeout(() -> delegate.call(toolInput), toolInput);
        log(toolInput, result);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (approvalService != null) {
            var decision = requestApproval(toolInput);
            if (!decision.approved()) return buildRejectionResult(toolInput, decision.note());
            var result = executeWithTimeout(() -> delegate.call(toolInput, toolContext), toolInput);
            var resultWithNote = appendNote(result, decision.note());
            log(toolInput, resultWithNote);
            return resultWithNote;
        }
        var result = executeWithTimeout(() -> delegate.call(toolInput, toolContext), toolInput);
        log(toolInput, result);
        return result;
    }

    private String executeWithTimeout(java.util.function.Supplier<String> execution, String toolInput) {
        if (toolExecutionTimeoutSeconds <= 0) {
            return execution.get();
        }
        var execFuture = CompletableFuture.supplyAsync(execution);
        try {
            return execFuture.get(toolExecutionTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            execFuture.cancel(true);
            return buildTimeoutResult(toolInput);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildTimeoutResult(toolInput);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    private ToolApprovalService.ApprovalDecision requestApproval(String toolInput) {
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
            return new ToolApprovalService.ApprovalDecision(false, "");
        } catch (ExecutionException e) {
            return new ToolApprovalService.ApprovalDecision(false, "");
        }
    }

    private String buildRejectionResult(String toolInput, String note) {
        var rejected = appendNote("Tool call rejected by user.", note);
        log(toolInput, rejected);
        return appendNote("Tool call was rejected by the user.", note);
    }

    private String buildTimeoutResult(String toolInput) {
        var toolName = delegate.getToolDefinition().name();
        var label = serverName != null ? serverName + " > " + toolName : toolName;
        var content = "[" + label + "]\nInput: " + toolInput
                + "\nOutput: Tool execution timed out after " + toolExecutionTimeoutSeconds + "s.";
        toolLog.add(ChatMessage.of("tool", content));
        if (onToolChunk != null) {
            onToolChunk.accept(new StreamChunk(StreamChunk.TYPE_TOOL, content));
        }
        return "Tool execution timed out.";
    }

    private static String appendNote(String output, String note) {
        return (note != null && !note.isBlank()) ? output + "\nNote: " + note : output;
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
