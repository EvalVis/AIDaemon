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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long NO_TIMEOUT = 0L;
    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

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

    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@PathVariable String id, @RequestBody MessageRequest request) {
        var useNoTimeout = manualApprove || shellAccessService.isEnabled();
        var emitter = new SseEmitter(useNoTimeout ? NO_TIMEOUT : DEFAULT_TIMEOUT_MS);
        conversationService.sendMessageStream(id, request.message(), request.fileIds(), request.notifyParticipants())
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
