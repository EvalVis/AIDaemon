package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.chat.Conversation;
import com.programmersdiary.aidaemon.chat.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@RequestBody Map<String, String> request) {
        var conversation = conversationService.create(request.get("providerId"));
        return Map.of("conversationId", conversation.id(), "providerId", conversation.providerId());
    }

    @PostMapping("/{id}/messages")
    public Map<String, String> sendMessage(@PathVariable String id, @RequestBody Map<String, String> request) {
        var response = conversationService.sendMessage(id, request.get("message"));
        return Map.of("response", response);
    }

    @GetMapping
    public List<Conversation> list() {
        return conversationService.listAll();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        conversationService.delete(id);
    }
}
