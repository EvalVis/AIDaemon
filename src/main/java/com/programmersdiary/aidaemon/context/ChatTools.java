package com.programmersdiary.aidaemon.context;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.stream.Collectors;

public class ChatTools {

    private final ContextService contextService;

    public ChatTools(ContextService contextService) {
        this.contextService = contextService;
    }

    @Tool(description = "Save information to persistent memory. Use when the user asks you to remember something.")
    public String saveMemory(
            @ToolParam(description = "Short descriptive key") String key,
            @ToolParam(description = "The value to remember") String value) {
        contextService.saveMemory(key, value);
        return "Saved to memory: " + key + " = " + value;
    }

    @Tool(description = "Read a file from the context folder.")
    public String readContextFile(
            @ToolParam(description = "Name of the file to read") String filename) {
        return contextService.readFile(filename);
    }

    @Tool(description = "List available files in the context folder.")
    public String listContextFiles() {
        var files = contextService.listFiles();
        if (files.isEmpty()) {
            return "No files in context folder.";
        }
        return "Available files:\n" + files.stream()
                .collect(Collectors.joining("\n"));
    }
}
