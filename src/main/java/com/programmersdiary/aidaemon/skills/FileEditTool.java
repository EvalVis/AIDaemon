package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.chat.StreamChunk;
import com.programmersdiary.aidaemon.chat.ToolApprovalService.ApprovalDecision;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

public class FileEditTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ShellAccessService shellAccessService;
    private final Function<String, CompletableFuture<ApprovalDecision>> requestApproval;
    private final Consumer<StreamChunk> onChunk;

    public FileEditTool(ShellAccessService shellAccessService,
                        Function<String, CompletableFuture<ApprovalDecision>> requestApproval,
                        Consumer<StreamChunk> onChunk) {
        this.shellAccessService = shellAccessService;
        this.requestApproval = requestApproval;
        this.onChunk = onChunk;
    }

    @Tool(description = "Create a new file with the specified content. Shows a diff preview to the user before writing. Only works when shell access is enabled.")
    public String createFile(
            @ToolParam(description = "Absolute path of the file to create") String path,
            @ToolParam(description = "Content of the file") String content) {
        if (!shellAccessService.isEnabled()) {
            return "Error: Shell access is currently disabled.";
        }
        return proposeAndExecute("createFile", "CREATE", path, null, content, () -> {
            var filePath = Path.of(path);
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.writeString(filePath, content);
            return "File created: " + path;
        });
    }

    @Tool(description = "Modify an existing file by replacing its entire content. Shows a diff preview to the user before writing. Only works when shell access is enabled.")
    public String modifyFile(
            @ToolParam(description = "Absolute path of the file to modify") String path,
            @ToolParam(description = "New content for the file") String newContent) {
        if (!shellAccessService.isEnabled()) {
            return "Error: Shell access is currently disabled.";
        }
        var oldContent = readFileOrEmpty(path);
        return proposeAndExecute("modifyFile", "MODIFY", path, oldContent, newContent, () -> {
            Files.writeString(Path.of(path), newContent);
            return "File modified: " + path;
        });
    }

    @Tool(description = "Edit specific lines of an existing file by providing a list of line-number/newContent pairs. Each pair replaces that line (1-based) with the given content. Shows a diff preview to the user before writing. Only works when shell access is enabled.")
    public String editFile(
            @ToolParam(description = "Absolute path of the file to edit") String path,
            @ToolParam(description = "List of line edits to apply") List<FileEdit> edits) {
        if (!shellAccessService.isEnabled()) {
            return "Error: Shell access is currently disabled.";
        }
        var oldContent = readFileOrEmpty(path);
        var patched = applyEdits(oldContent, edits);
        if (patched == null) {
            return "Error: one or more line numbers are out of range.";
        }
        var newContent = patched;
        return proposeAndExecute("editFile", "MODIFY", path, oldContent, newContent, () -> {
            Files.writeString(Path.of(path), newContent);
            return "File edited: " + path;
        });
    }

    private static String applyEdits(String content, List<FileEdit> edits) {
        var lines = new ArrayList<>(List.of(content.split("\n", -1)));
        for (var edit : edits) {
            var index = edit.lineNumber() - 1;
            if (index < 0 || index >= lines.size()) {
                return null;
            }
            lines.set(index, edit.newContent());
        }
        return String.join("\n", lines);
    }

    public record FileEdit(
            @ToolParam(description = "1-based line number to replace") int lineNumber,
            @ToolParam(description = "New content for that line") String newContent) {}

    @Tool(description = "Delete an existing file. Shows the current file content to the user before deleting. Only works when shell access is enabled.")
    public String deleteFile(
            @ToolParam(description = "Absolute path of the file to delete") String path) {
        if (!shellAccessService.isEnabled()) {
            return "Error: Shell access is currently disabled.";
        }
        var oldContent = readFileOrEmpty(path);
        return proposeAndExecute("deleteFile", "DELETE", path, oldContent, null, () -> {
            Files.delete(Path.of(path));
            return "File deleted: " + path;
        });
    }

    private String proposeAndExecute(String toolName, String operation, String path,
                                     String oldContent, String newContent, FileOperation execution) {
        var changeId = UUID.randomUUID().toString();
        var pendingJson = buildPendingJson(changeId, toolName, operation, path, oldContent, newContent);
        var future = requestApproval.apply(changeId);
        onChunk.accept(new StreamChunk(StreamChunk.TYPE_TOOL_PENDING, pendingJson));
        try {
            var decision = future.get();
            if (!decision.approved()) {
                var note = decision.note();
                return (note != null && !note.isBlank()) ? "File change rejected by user. Note: " + note : "File change rejected by user.";
            }
            return execution.execute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "File change interrupted.";
        } catch (ExecutionException e) {
            return "Error waiting for approval: " + e.getCause().getMessage();
        } catch (Exception e) {
            return "Error executing file operation: " + e.getMessage();
        }
    }

    private String readFileOrEmpty(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            return "";
        }
    }

    private static String buildPendingJson(String approvalId, String toolName, String operation,
                                           String path, String oldContent, String newContent) {
        try {
            var map = new LinkedHashMap<String, Object>();
            map.put("approvalId", approvalId);
            map.put("toolName", toolName);
            map.put("toolInput", buildToolInputJson(path, oldContent, newContent));
            map.put("operation", operation);
            map.put("path", path);
            map.put("oldContent", oldContent);
            map.put("newContent", newContent);
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String buildToolInputJson(String path, String oldContent, String newContent) {
        try {
            var map = new LinkedHashMap<String, Object>();
            map.put("path", path);
            if (newContent != null) map.put("newContent", newContent);
            if (oldContent != null) map.put("oldContent", oldContent);
            return JSON.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    @FunctionalInterface
    private interface FileOperation {
        String execute() throws Exception;
    }
}
