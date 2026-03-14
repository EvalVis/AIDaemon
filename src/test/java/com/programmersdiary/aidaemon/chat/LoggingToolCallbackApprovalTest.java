package com.programmersdiary.aidaemon.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoggingToolCallbackApprovalTest {

    private static ToolCallback stubDelegate(String name, String result) {
        var def = ToolDefinition.builder().name(name).description("desc").inputSchema("{}").build();
        var cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(def);
        when(cb.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        when(cb.call(anyString())).thenReturn(result);
        when(cb.call(anyString(), any(ToolContext.class))).thenReturn(result);
        return cb;
    }

    @Test
    void withNullApprovalService_executesNormally() {
        var delegate = stubDelegate("myTool", "result");
        var chunks = new ArrayList<StreamChunk>();
        var toolLog = new ArrayList<ChatMessage>();
        var cb = new LoggingToolCallback(delegate, toolLog, null, chunks::add, null);

        var result = cb.call("{\"x\":1}");

        assertEquals("result", result);
        assertEquals(1, chunks.size());
        assertEquals(StreamChunk.TYPE_TOOL, chunks.get(0).type());
        verify(delegate).call("{\"x\":1}");
    }

    @Test
    void whenApproved_emitsPendingChunkThenExecutesDelegate() throws Exception {
        var delegate = stubDelegate("myTool", "ok");
        var chunks = new ArrayList<StreamChunk>();
        var toolLog = new ArrayList<ChatMessage>();
        var approvalService = new ToolApprovalService();
        var cb = new LoggingToolCallback(delegate, toolLog, null, chunks::add, approvalService);

        // Approve asynchronously after a short delay
        var capturedId = new AtomicReference<String>();
        Executors.newSingleThreadExecutor().submit(() -> {
            // Wait until a pending approval appears
            while (approvalService.hasPending()) {
                // poll
            }
            // Actually wait for the future to be registered
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            // Approve all pending (we'll use the captured ID from the chunk)
            if (capturedId.get() != null) {
                approvalService.approve(capturedId.get());
            }
        });

        // Capture the approvalId from the pending chunk before calling
        // We run the call in a thread so we can approve concurrently
        var callThread = Executors.newSingleThreadExecutor();
        var futureResult = callThread.submit(() -> {
            // Need to capture approvalId from the first chunk emitted
            var capturingCb = new LoggingToolCallback(delegate, toolLog, null, chunk -> {
                chunks.add(chunk);
                if (StreamChunk.TYPE_TOOL_PENDING.equals(chunk.type())) {
                    try {
                        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(chunk.content());
                        capturedId.set(node.get("approvalId").asText());
                        // Approve right away
                        approvalService.approve(capturedId.get());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }, approvalService);
            return capturingCb.call("{\"x\":1}");
        });

        var result = futureResult.get(5, TimeUnit.SECONDS);

        assertEquals("ok", result);
        assertTrue(chunks.stream().anyMatch(c -> StreamChunk.TYPE_TOOL_PENDING.equals(c.type())));
        assertTrue(chunks.stream().anyMatch(c -> StreamChunk.TYPE_TOOL.equals(c.type())));
        verify(delegate).call("{\"x\":1}");
    }

    @Test
    void whenApprovedButExecutionTimesOut_emitsTimeoutChunk() throws Exception {
        var def = ToolDefinition.builder().name("slowTool").description("desc").inputSchema("{}").build();
        var delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(def);
        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        when(delegate.call(anyString())).thenAnswer(inv -> {
            Thread.sleep(10_000); // hangs
            return "never";
        });

        var chunks = new ArrayList<StreamChunk>();
        var approvalService = new ToolApprovalService();

        var callThread = Executors.newSingleThreadExecutor();
        var futureResult = callThread.submit(() -> {
            var cb = new LoggingToolCallback(delegate, new ArrayList<>(), null, chunk -> {
                chunks.add(chunk);
                if (StreamChunk.TYPE_TOOL_PENDING.equals(chunk.type())) {
                    try {
                        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(chunk.content());
                        approvalService.approve(node.get("approvalId").asText());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }, approvalService, 1 /* 1 second execution timeout */);
            return cb.call("{\"x\":1}");
        });

        var result = futureResult.get(5, TimeUnit.SECONDS);

        assertTrue(result.contains("timed out"));
        assertTrue(chunks.stream().anyMatch(c -> StreamChunk.TYPE_TOOL.equals(c.type()) && c.content().contains("timed out")));
    }

    @Test
    void whenRejected_skipsDelegateAndEmitsRejectionChunk() throws Exception {
        var delegate = stubDelegate("myTool", "should-not-run");
        var chunks = new ArrayList<StreamChunk>();
        var toolLog = new ArrayList<ChatMessage>();
        var approvalService = new ToolApprovalService();
        var capturedId = new AtomicReference<String>();

        var callThread = Executors.newSingleThreadExecutor();
        var futureResult = callThread.submit(() -> {
            var cb = new LoggingToolCallback(delegate, toolLog, null, chunk -> {
                chunks.add(chunk);
                if (StreamChunk.TYPE_TOOL_PENDING.equals(chunk.type())) {
                    try {
                        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(chunk.content());
                        capturedId.set(node.get("approvalId").asText());
                        approvalService.reject(capturedId.get());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }, approvalService);
            return cb.call("{\"x\":1}");
        });

        var result = futureResult.get(5, TimeUnit.SECONDS);

        assertTrue(result.contains("rejected"));
        assertTrue(chunks.stream().anyMatch(c -> StreamChunk.TYPE_TOOL_PENDING.equals(c.type())));
        assertTrue(chunks.stream().anyMatch(c -> StreamChunk.TYPE_TOOL.equals(c.type())
                && c.content().contains("rejected")));
        verify(delegate, never()).call(anyString());
    }
}
