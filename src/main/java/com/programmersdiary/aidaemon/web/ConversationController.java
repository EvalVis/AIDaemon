package com.programmersdiary.aidaemon.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.chat.Conversation;
import com.programmersdiary.aidaemon.chat.ConversationService;
import com.programmersdiary.aidaemon.chat.StreamChunk;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@RequestBody Map<String, String> request) {
        var name = request.getOrDefault("name", "Untitled");
        var conversation = conversationService.create(name, request.get("providerId"));
        return Map.of("conversationId", conversation.id(), "name", conversation.name(), "providerId", conversation.providerId());
    }

    @PostMapping("/{id}/messages")
    public Map<String, String> sendMessage(@PathVariable String id, @RequestBody Map<String, String> request) {
        var response = conversationService.sendMessage(id, request.get("message"));
        return Map.of("response", response);
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@PathVariable String id, @RequestBody Map<String, String> request) {
        var emitter = new SseEmitter(300_000L);
        conversationService.sendMessageStream(id, request.get("message"))
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().data(OBJECT_MAPPER.writeValueAsString(chunk)));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );
        return emitter;
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
