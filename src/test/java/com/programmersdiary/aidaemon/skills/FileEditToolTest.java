package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.chat.StreamChunk;
import com.programmersdiary.aidaemon.chat.ToolApprovalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileEditToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void createFile_whenShellDisabled_returnsError() {
        var tool = new FileEditTool(shellAccess(false), id -> new CompletableFuture<>(), chunk -> {});
        var result = tool.createFile(tempDir.resolve("test.txt").toString(), "content");
        assertTrue(result.toLowerCase().contains("disabled") || result.toLowerCase().contains("error"));
    }

    @Test
    void modifyFile_whenShellDisabled_returnsError() {
        var tool = new FileEditTool(shellAccess(false), id -> new CompletableFuture<>(), chunk -> {});
        var result = tool.modifyFile(tempDir.resolve("test.txt").toString(), "content");
        assertTrue(result.toLowerCase().contains("disabled") || result.toLowerCase().contains("error"));
    }

    @Test
    void deleteFile_whenShellDisabled_returnsError() {
        var tool = new FileEditTool(shellAccess(false), id -> new CompletableFuture<>(), chunk -> {});
        var result = tool.deleteFile(tempDir.resolve("test.txt").toString());
        assertTrue(result.toLowerCase().contains("disabled") || result.toLowerCase().contains("error"));
    }

    @Test
    void createFile_whenApproved_writesFileAndReturnsSuccess() throws Exception {
        var filePath = tempDir.resolve("new.txt").toString();
        var result = callToolWithDecision(
                (svc, chunks) -> new FileEditTool(shellAccess(true), svc::requestApproval, chunks::add),
                tool -> tool.createFile(filePath, "hello world"),
                true
        );
        assertTrue(Files.exists(Path.of(filePath)));
        assertEquals("hello world", Files.readString(Path.of(filePath)));
        assertTrue(result.toLowerCase().contains("created") || result.toLowerCase().contains("success"));
    }

    @Test
    void createFile_whenRejected_doesNotWriteFile() throws Exception {
        var filePath = tempDir.resolve("rejected.txt").toString();
        var result = callToolWithDecision(
                (svc, chunks) -> new FileEditTool(shellAccess(true), svc::requestApproval, chunks::add),
                tool -> tool.createFile(filePath, "content"),
                false
        );
        assertFalse(Files.exists(Path.of(filePath)));
        assertTrue(result.toLowerCase().contains("rejected") || result.toLowerCase().contains("cancel"));
    }

    @Test
    void modifyFile_whenApproved_overwritesFile() throws Exception {
        var filePath = tempDir.resolve("existing.txt");
        Files.writeString(filePath, "old content");

        callToolWithDecision(
                (svc, chunks) -> new FileEditTool(shellAccess(true), svc::requestApproval, chunks::add),
                tool -> tool.modifyFile(filePath.toString(), "new content"),
                true
        );

        assertEquals("new content", Files.readString(filePath));
    }

    @Test
    void modifyFile_whenFileDoesNotExist_treatsOldContentAsEmpty() throws Exception {
        var filePath = tempDir.resolve("nonexistent.txt").toString();
        var capturedJson = capturePendingChunkJson(tool -> tool.modifyFile(filePath, "new content"));
        assertEquals("", capturedJson.get("oldContent").asText());
        assertEquals("new content", capturedJson.get("newContent").asText());
    }

    @Test
    void deleteFile_whenApproved_deletesFile() throws Exception {
        var filePath = tempDir.resolve("to-delete.txt");
        Files.writeString(filePath, "some content");

        callToolWithDecision(
                (svc, chunks) -> new FileEditTool(shellAccess(true), svc::requestApproval, chunks::add),
                tool -> tool.deleteFile(filePath.toString()),
                true
        );

        assertFalse(Files.exists(filePath));
    }

    @Test
    void deleteFile_whenRejected_doesNotDeleteFile() throws Exception {
        var filePath = tempDir.resolve("keep.txt");
        Files.writeString(filePath, "keep me");

        callToolWithDecision(
                (svc, chunks) -> new FileEditTool(shellAccess(true), svc::requestApproval, chunks::add),
                tool -> tool.deleteFile(filePath.toString()),
                false
        );

        assertTrue(Files.exists(filePath));
    }

    @Test
    void createFile_emitsPendingChunkWithCorrectOperationAndPath() throws Exception {
        var filePath = tempDir.resolve("emit-test.txt").toString();
        var json = capturePendingChunkJson(tool -> tool.createFile(filePath, "content"));
        assertEquals("CREATE", json.get("operation").asText());
        assertEquals(filePath, json.get("path").asText());
        assertTrue(json.get("oldContent").isNull());
        assertEquals("content", json.get("newContent").asText());
        assertFalse(json.get("approvalId").asText().isEmpty());
    }

    @Test
    void modifyFile_emitsPendingChunkWithOldAndNewContent() throws Exception {
        var filePath = tempDir.resolve("modify-emit.txt");
        Files.writeString(filePath, "old");
        var json = capturePendingChunkJson(tool -> tool.modifyFile(filePath.toString(), "new"));
        assertEquals("MODIFY", json.get("operation").asText());
        assertEquals("old", json.get("oldContent").asText());
        assertEquals("new", json.get("newContent").asText());
    }

    @Test
    void deleteFile_emitsPendingChunkWithOldContentAndNullNew() throws Exception {
        var filePath = tempDir.resolve("del-emit.txt");
        Files.writeString(filePath, "bye");
        var json = capturePendingChunkJson(tool -> tool.deleteFile(filePath.toString()));
        assertEquals("DELETE", json.get("operation").asText());
        assertEquals("bye", json.get("oldContent").asText());
        assertTrue(json.get("newContent").isNull());
    }

    // --- helpers ---

    private ShellAccessService shellAccess(boolean enabled) {
        return new ShellAccessService(enabled);
    }

    @FunctionalInterface
    interface ToolFactory {
        FileEditTool create(ToolApprovalService svc, ArrayList<StreamChunk> chunks);
    }

    @FunctionalInterface
    interface ToolAction {
        String call(FileEditTool tool);
    }

    private String callToolWithDecision(ToolFactory factory, ToolAction action, boolean approve) throws Exception {
        var approvalService = new ToolApprovalService();
        var chunks = new ArrayList<StreamChunk>();
        var tool = factory.create(approvalService, chunks);

        var executor = Executors.newSingleThreadExecutor();
        var futureResult = executor.submit(() -> action.call(tool));

        var pendingChunk = waitForPendingChunk(chunks);
        var changeId = JSON.readTree(pendingChunk.content()).get("approvalId").asText();
        if (approve) {
            approvalService.approve(changeId);
        } else {
            approvalService.reject(changeId);
        }

        return futureResult.get(5, TimeUnit.SECONDS);
    }

    private com.fasterxml.jackson.databind.JsonNode capturePendingChunkJson(ToolAction action) throws Exception {
        var approvalService = new ToolApprovalService();
        var capturedChunk = new AtomicReference<StreamChunk>();

        var executor = Executors.newSingleThreadExecutor();
        var futureResult = executor.submit(() -> {
            var tool = new FileEditTool(shellAccess(true), approvalService::requestApproval, chunk -> {
                capturedChunk.set(chunk);
                if (StreamChunk.TYPE_TOOL_PENDING.equals(chunk.type())) {
                    try {
                        var changeId = JSON.readTree(chunk.content()).get("approvalId").asText();
                        approvalService.approve(changeId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            return action.call(tool);
        });
        futureResult.get(5, TimeUnit.SECONDS);

        assertNotNull(capturedChunk.get(), "Expected FILE_CHANGE_PENDING chunk to be emitted");
        return JSON.readTree(capturedChunk.get().content());
    }

    private StreamChunk waitForPendingChunk(ArrayList<StreamChunk> chunks) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var found = chunks.stream()
                    .filter(c -> StreamChunk.TYPE_TOOL_PENDING.equals(c.type()))
                    .findFirst();
            if (found.isPresent()) return found.get();
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out waiting for FILE_CHANGE_PENDING chunk");
    }
}
