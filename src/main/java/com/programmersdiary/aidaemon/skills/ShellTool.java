package com.programmersdiary.aidaemon.skills;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ShellTool {

    private static final long TIMEOUT_SECONDS = 60;

    private final ShellAccessService shellAccessService;

    public ShellTool(ShellAccessService shellAccessService) {
        this.shellAccessService = shellAccessService;
    }

    @Tool(description = "Execute a shell command on the host system and return its output. Use this to run CLI tools like git, gh, docker, curl, etc.")
    public String executeCommand(
            @ToolParam(description = "The command to execute") String command) {
        if (!shellAccessService.isEnabled()) {
            return "Error: Shell access is currently disabled.";
        }
        try {
            var isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            var processBuilder = isWindows
                    ? new ProcessBuilder("cmd", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            processBuilder.redirectErrorStream(true);

            var process = processBuilder.start();
            var output = new String(process.getInputStream().readAllBytes());
            var finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return "Command timed out after " + TIMEOUT_SECONDS + " seconds.\nPartial output:\n" + output;
            }

            var exitCode = process.exitValue();
            if (exitCode != 0) {
                return "Exit code: " + exitCode + "\n" + output;
            }
            return output.isBlank() ? "(no output)" : output;
        } catch (IOException | InterruptedException e) {
            return "Error executing command: " + e.getMessage();
        }
    }
}
