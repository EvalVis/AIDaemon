package com.programmersdiary.aidaemon.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.programmersdiary.aidaemon.chat.ToolApprovalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tools")
public class ToolApprovalController {

    record NoteRequest(@JsonProperty("note") String note) {
        String noteOrEmpty() { return note != null ? note.trim() : ""; }
    }

    private final ToolApprovalService approvalService;

    public ToolApprovalController(ToolApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/{approvalId}/approve")
    public ResponseEntity<Void> approve(@PathVariable String approvalId,
                                        @RequestBody(required = false) NoteRequest body) {
        approvalService.approve(approvalId, body != null ? body.noteOrEmpty() : "");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{approvalId}/reject")
    public ResponseEntity<Void> reject(@PathVariable String approvalId,
                                       @RequestBody(required = false) NoteRequest body) {
        approvalService.reject(approvalId, body != null ? body.noteOrEmpty() : "");
        return ResponseEntity.ok().build();
    }
}
