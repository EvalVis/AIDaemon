package com.programmersdiary.aidaemon.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.chat.Conversation;
import com.programmersdiary.aidaemon.chat.ConversationService;
import com.programmersdiary.aidaemon.chat.StreamChunk;
import com.programmersdiary.aidaemon.skills.ShellAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

record MessageRequest(String message, List<String> fileIds) {
    MessageRequest { fileIds = fileIds != null ? fileIds : List.of(); }
}

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long NO_TIMEOUT = 0L;
    private static final long DEFAULT_TIMEOUT_MS = 300_000L;
    private static final String DEFAULT_USER_PARTICIPANT = "user";

    private final ConversationService conversationService;
    private final boolean manualApprove;
    private final ShellAccessService shellAccessService;

    public ConversationController(ConversationService conversationService,
                                  ShellAccessService shellAccessService,
                                  @Value("${aidaemon.manual-approve:false}") boolean manualApprove) {
        this.conversationService = conversationService;
        this.shellAccessService = shellAccessService;
        this.manualApprove = manualApprove;
    }

    @GetMapping("/direct/{botName}")
    public Conversation getOrCreateDirect(@PathVariable String botName,
                                         @RequestParam(required = false) String providerId) {
        return conversationService.getOrCreateDirect(DEFAULT_USER_PARTICIPANT, botName, providerId);
    }

    @PatchMapping("/{id}")
    public Conversation update(@PathVariable String id, @RequestBody Map<String, String> request) {
        var conversation = conversationService.get(id);
        var providerId = conversation.providerId();
        if (request.containsKey("providerId")) {
            var raw = request.get("providerId");
            providerId = (raw == null || raw.isBlank()) ? null : raw;
        }
        var updated = new Conversation(
                conversation.id(), conversation.name(), providerId,
                conversation.messages(), conversation.createdAtMillis(),
                conversation.participant1(), conversation.participant2(), conversation.direct());
        conversationService.save(updated);
        return updated;
    }

    @PostMapping("/{id}/messages")
    public Map<String, String> sendMessage(@PathVariable String id, @RequestBody MessageRequest request) {
        var response = conversationService.sendMessage(id, request.message(), request.fileIds());
        return Map.of("response", response);
    }

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@PathVariable String id, @RequestBody MessageRequest request) {
        var useNoTimeout = manualApprove || shellAccessService.isEnabled();
        var emitter = new SseEmitter(useNoTimeout ? NO_TIMEOUT : DEFAULT_TIMEOUT_MS);
        conversationService.sendMessageStream(id, request.message(), request.fileIds())
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
}
