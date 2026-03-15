package com.programmersdiary.aidaemon.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    void whenApproved_executesDelegate() throws Exception {
        var delegate = stubDelegate("myTool", "ok");
        var toolLog = new ArrayList<ChatMessage>();
        var approvalService = new ToolApprovalService();

        var callThread = Executors.newSingleThreadExecutor();
        var futureResult = callThread.submit(() -> {
            var cb = new LoggingToolCallback(delegate, toolLog, null, null, approvalService);
            return cb.call("{\"x\":1}");
        });

        awaitAndApprove(approvalService);

        var result = futureResult.get(5, TimeUnit.SECONDS);
        assertEquals("ok", result);
        verify(delegate).call("{\"x\":1}");
    }

    @Test
    void whenApprovedButExecutionTimesOut_returnsTimeout() throws Exception {
        var def = ToolDefinition.builder().name("slowTool").description("desc").inputSchema("{}").build();
        var delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(def);
        when(delegate.getToolMetadata()).thenReturn(ToolMetadata.builder().returnDirect(false).build());
        when(delegate.call(anyString())).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return "never";
        });

        var approvalService = new ToolApprovalService();

        var callThread = Executors.newSingleThreadExecutor();
        var futureResult = callThread.submit(() -> {
            var cb = new LoggingToolCallback(delegate, new ArrayList<>(), null, null, approvalService, 1);
            return cb.call("{\"x\":1}");
        });

        awaitAndApprove(approvalService);

        var result = futureResult.get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("timed out"));
    }

    @Test
    void whenRejected_skipsDelegateAndReturnsRejection() throws Exception {
        var delegate = stubDelegate("myTool", "should-not-run");
        var toolLog = new ArrayList<ChatMessage>();
        var approvalService = new ToolApprovalService();

        var callThread = Executors.newSingleThreadExecutor();
        var futureResult = callThread.submit(() -> {
            var cb = new LoggingToolCallback(delegate, toolLog, null, null, approvalService);
            return cb.call("{\"x\":1}");
        });

        awaitAndReject(approvalService);

        var result = futureResult.get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("rejected"));
        verify(delegate, never()).call(anyString());
    }

    private void awaitAndApprove(ToolApprovalService service) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var pending = service.listPending();
            if (!pending.isEmpty()) {
                service.approve(pending.get(0).approvalId(), "");
                return;
            }
            Thread.sleep(50);
        }
        fail("No pending approval appeared");
    }

    private void awaitAndReject(ToolApprovalService service) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var pending = service.listPending();
            if (!pending.isEmpty()) {
                service.reject(pending.get(0).approvalId(), "");
                return;
            }
            Thread.sleep(50);
        }
        fail("No pending approval appeared");
    }
}
