package com.programmersdiary.aidaemon.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolApprovalServiceTest {

    @Test
    void requestApproval_returnsPendingFuture() {
        var service = new ToolApprovalService();
        var future = service.requestApproval("id1");
        assertFalse(future.isDone());
    }

    @Test
    void approve_completesFutureWithTrue() throws Exception {
        var service = new ToolApprovalService();
        var future = service.requestApproval("id1");
        service.approve("id1");
        assertTrue(future.isDone());
        assertTrue(future.get());
    }

    @Test
    void reject_completesFutureWithFalse() throws Exception {
        var service = new ToolApprovalService();
        var future = service.requestApproval("id1");
        service.reject("id1");
        assertTrue(future.isDone());
        assertFalse(future.get());
    }

    @Test
    void approveUnknownId_isNoOp() {
        var service = new ToolApprovalService();
        assertDoesNotThrow(() -> service.approve("nonexistent"));
    }

    @Test
    void rejectUnknownId_isNoOp() {
        var service = new ToolApprovalService();
        assertDoesNotThrow(() -> service.reject("nonexistent"));
    }

    @Test
    void approveRemovesPendingEntry() {
        var service = new ToolApprovalService();
        service.requestApproval("id1");
        service.approve("id1");
        // Second approve is a no-op — should not throw
        assertDoesNotThrow(() -> service.approve("id1"));
    }
}
