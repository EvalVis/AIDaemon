package com.programmersdiary.aidaemon.chat;

import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolApprovalService {

    public record ApprovalDecision(boolean approved, String note) {}

    private final ConcurrentHashMap<String, CompletableFuture<ApprovalDecision>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<ApprovalDecision> requestApproval(String approvalId) {
        var future = new CompletableFuture<ApprovalDecision>();
        pending.put(approvalId, future);
        return future;
    }

    public void approve(String approvalId, String note) {
        var future = pending.remove(approvalId);
        if (future != null) {
            future.complete(new ApprovalDecision(true, note));
        }
    }

    public void reject(String approvalId, String note) {
        var future = pending.remove(approvalId);
        if (future != null) {
            future.complete(new ApprovalDecision(false, note));
        }
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }
}
