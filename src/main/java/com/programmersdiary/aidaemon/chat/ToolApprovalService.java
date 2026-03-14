package com.programmersdiary.aidaemon.chat;

import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolApprovalService {

    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<Boolean> requestApproval(String approvalId) {
        var future = new CompletableFuture<Boolean>();
        pending.put(approvalId, future);
        return future;
    }

    public void approve(String approvalId) {
        var future = pending.remove(approvalId);
        if (future != null) {
            future.complete(true);
        }
    }

    public void reject(String approvalId) {
        var future = pending.remove(approvalId);
        if (future != null) {
            future.complete(false);
        }
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }
}
