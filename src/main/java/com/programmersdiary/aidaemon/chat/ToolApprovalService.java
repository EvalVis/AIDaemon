package com.programmersdiary.aidaemon.chat;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolApprovalService {

    public record ApprovalDecision(boolean approved, String note) {}

    public record PendingApproval(String approvalId, String toolName, String toolInput) {}

    private record PendingEntry(CompletableFuture<ApprovalDecision> future, PendingApproval info) {}

    private final ConcurrentHashMap<String, PendingEntry> pending = new ConcurrentHashMap<>();

    public CompletableFuture<ApprovalDecision> requestApproval(String approvalId) {
        return requestApproval(approvalId, approvalId, "");
    }

    public CompletableFuture<ApprovalDecision> requestApproval(String approvalId, String toolName, String toolInput) {
        var future = new CompletableFuture<ApprovalDecision>();
        pending.put(approvalId, new PendingEntry(future, new PendingApproval(approvalId, toolName, toolInput)));
        return future;
    }

    public void approve(String approvalId, String note) {
        var entry = pending.remove(approvalId);
        if (entry != null) {
            entry.future().complete(new ApprovalDecision(true, note));
        }
    }

    public void reject(String approvalId, String note) {
        var entry = pending.remove(approvalId);
        if (entry != null) {
            entry.future().complete(new ApprovalDecision(false, note));
        }
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public List<PendingApproval> listPending() {
        return pending.values().stream().map(PendingEntry::info).toList();
    }
}
