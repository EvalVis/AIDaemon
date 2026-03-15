package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.chat.Conversation;
import com.programmersdiary.aidaemon.chat.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

record MessageRequest(String message, List<String> fileIds, List<String> notifyParticipants) {
    MessageRequest {
        fileIds = fileIds != null ? fileIds : List.of();
        notifyParticipants = notifyParticipants != null ? notifyParticipants : List.of();
    }
}

record CreateConversationRequest(String name, String providerId, List<String> participants) {
}

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@RequestBody CreateConversationRequest request) {
        return conversationService.createConversation(request.name(), request.providerId(),
                request.participants() != null ? request.participants() : List.of());
    }

    @PostMapping("/{id}/participants")
    public Conversation addParticipant(@PathVariable String id, @RequestBody Map<String, String> request) {
        var participantName = request.get("participantName");
        if (participantName == null || participantName.isBlank()) {
            throw new IllegalArgumentException("participantName is required");
        }
        return conversationService.addParticipant(id, participantName);
    }

    @PatchMapping("/{id}")
    public Conversation update(@PathVariable String id, @RequestBody Map<String, String> request) {
        var conversation = conversationService.get(id);
        var providerId = conversation.providerId();
        if (request.containsKey("providerId")) {
            var raw = request.get("providerId");
            providerId = (raw == null || raw.isBlank()) ? null : raw;
        }
        var updated = new Conversation(conversation.id(), conversation.name(), providerId,
                conversation.messages(), conversation.createdAtMillis(), conversation.participants());
        conversationService.save(updated);
        return updated;
    }

    @PostMapping("/{id}/messages")
    public void sendMessage(@PathVariable String id, @RequestBody MessageRequest request) {
        conversationService.sendMessage(id, request.message(), request.fileIds(), request.notifyParticipants());
    }

    @GetMapping
    public List<Conversation> list(@RequestParam(required = false) String participant) {
        if (participant != null && !participant.isBlank()) {
            return conversationService.listForParticipant(participant);
        }
        return conversationService.listAll();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        conversationService.delete(id);
    }

    @GetMapping("/{id}")
    public Conversation get(@PathVariable String id) {
        return conversationService.get(id);
    }
}
