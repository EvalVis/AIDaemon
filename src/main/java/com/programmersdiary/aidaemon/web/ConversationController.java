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
import java.util.HashMap;
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
    public Map<String, Object> create(@RequestBody Map<String, String> request) {
        var name = request.getOrDefault("name", "Untitled");
        var providerId = request.get("providerId");
        if (providerId != null && providerId.isBlank()) providerId = null;
        var botName = request.get("botName");
        if (botName != null && (botName.isBlank() || "default".equalsIgnoreCase(botName))) botName = null;
        var conversation = conversationService.create(name, providerId, botName, null);
        var out = new HashMap<String, Object>();
        out.put("conversationId", conversation.id());
        out.put("name", conversation.name());
        out.put("providerId", conversation.providerId());
        out.put("botName", conversation.botName());
        return out;
    }

    @PatchMapping("/{id}")
    public Conversation update(@PathVariable String id, @RequestBody Map<String, String> request) {
        var conversation = conversationService.get(id);
        var providerId = conversation.providerId();
        if (request.containsKey("providerId")) {
            var rawProviderId = request.get("providerId");
            providerId = (rawProviderId == null || rawProviderId.isBlank()) ? null : rawProviderId;
        }
        var botName = conversation.botName();
        if (request.containsKey("botName")) {
            var rawBot = request.get("botName");
            if (rawBot == null || rawBot.isBlank() || "default".equalsIgnoreCase(rawBot)) {
                botName = null;
            } else {
                botName = rawBot;
            }
        }
        var updated = new com.programmersdiary.aidaemon.chat.Conversation(
                conversation.id(),
                conversation.name(),
                providerId,
                botName,
                conversation.messages(),
                conversation.parentConversationId(),
                conversation.createdAtMillis(),
                conversation.participant1(),
                conversation.participant2());
        conversationService.save(updated);
        return updated;
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

    private static final String DEFAULT_USER_PARTICIPANT = "user";

    @GetMapping("/direct/{botName}")
    public Conversation getOrCreateDirect(@PathVariable String botName,
                                         @RequestParam(required = false) String providerId) {
        return conversationService.getOrCreateDirect(DEFAULT_USER_PARTICIPANT, botName, providerId);
    }
}
