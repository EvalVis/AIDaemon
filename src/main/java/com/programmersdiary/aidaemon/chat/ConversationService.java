package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final BotService botService;
    private final FileStorageService fileStorageService;

    public ConversationService(ConversationRepository conversationRepository,
                               BotService botService,
                               FileStorageService fileStorageService) {
        this.conversationRepository = conversationRepository;
        this.botService = botService;
        this.fileStorageService = fileStorageService;
    }

    public String sendMessageToParticipant(String callerBotName, String target, String message, String providerId) {
        if (callerBotName == null || callerBotName.isBlank()) {
            throw new IllegalArgumentException("Caller bot name is required");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Target participant is required");
        }
        if (callerBotName.equals(target)) {
            throw new IllegalArgumentException("Cannot message yourself");
        }
        var botNames = botService.listBots().stream().map(b -> b.name()).toList();
        if (!botNames.contains(callerBotName)) {
            throw new IllegalArgumentException("Caller bot not found: " + callerBotName);
        }
        if ("user".equalsIgnoreCase(target)) {
            var conv = getOrCreateDirect("user", callerBotName, providerId);
            conv.messages().add(ChatMessage.of(callerBotName, message));
            conversationRepository.save(conv);
            return "Message written to user conversation.";
        }
        if (!botNames.contains(target)) {
            throw new IllegalArgumentException("Target participant not found: " + target);
        }
        var conv = getOrCreateDirect(callerBotName, target, providerId);
        return sendMessage(conv.id(), callerBotName, message, List.of());
    }

    public Conversation getOrCreateDirect(String participant1, String participant2, String providerId) {
        if (participant1 == null || participant1.isBlank() || participant2 == null || participant2.isBlank()) {
            throw new IllegalArgumentException("Both participants are required");
        }
        validateParticipant(participant1);
        validateParticipant(participant2);
        var id = Conversation.canonicalId(participant1, participant2);
        var p1 = participant1.compareTo(participant2) <= 0 ? participant1 : participant2;
        var p2 = participant1.compareTo(participant2) <= 0 ? participant2 : participant1;
        return conversationRepository.findById(id)
                .map(conv -> {
                    if (providerId != null && !providerId.isBlank() && !providerId.equals(conv.providerId())) {
                        var updated = new Conversation(conv.id(), conv.name(), providerId,
                                conv.messages(), conv.createdAtMillis(),
                                conv.participant1(), conv.participant2(), conv.direct());
                        conversationRepository.save(updated);
                        return updated;
                    }
                    return conv;
                })
                .orElseGet(() -> {
                    var name = participant1 + " & " + participant2;
                    var conv = new Conversation(id, name, providerId, new ArrayList<>(),
                            System.currentTimeMillis(), p1, p2, true);
                    return conversationRepository.save(conv);
                });
    }

    private void validateParticipant(String participant) {
        if ("user".equalsIgnoreCase(participant)) return;
        if (!botService.listBots().stream().anyMatch(b -> participant.equals(b.name()))) {
            throw new IllegalArgumentException("Participant not found: " + participant);
        }
    }

    public void save(Conversation conversation) {
        conversationRepository.save(conversation);
    }

    public String sendMessage(String conversationId, String userMessage) {
        return sendMessage(conversationId, "user", userMessage, List.of());
    }

    public String sendMessage(String conversationId, String userMessage, List<String> fileIds) {
        return sendMessage(conversationId, "user", userMessage, fileIds);
    }

    public String sendMessage(String conversationId, String senderParticipant, String message, List<String> fileIds) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            throw new IllegalArgumentException("No agent selected for this conversation");
        }
        var files = resolveFiles(fileIds);
        conversation.messages().add(ChatMessage.ofWithFiles(senderParticipant, message, files));
        var replyingBotName = getOtherParticipant(conversation, senderParticipant);
        var bot = botService.getBot(replyingBotName);
        var senderIdentity = senderIdentity(conversation, replyingBotName);
        var result = bot.chat(conversation.providerId(), conversation.messages(), conversationId, senderIdentity);
        conversation.messages().add(ChatMessage.of(replyingBotName, result.assistantContent()));
        conversationRepository.save(conversation);
        return result.response();
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage) {
        return sendMessageStream(conversationId, userMessage, List.of());
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage, List<String> fileIds) {
        return sendMessageStream(conversationId, "user", userMessage, fileIds);
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String senderParticipant, String message, List<String> fileIds) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            return Flux.error(new IllegalArgumentException("No agent selected for this conversation"));
        }
        var files = resolveFiles(fileIds);
        conversation.messages().add(ChatMessage.ofWithFiles(senderParticipant, message, files));
        conversationRepository.save(conversation);

        var replyingBotName = getOtherParticipant(conversation, senderParticipant);
        var bot = botService.getBot(replyingBotName);
        var senderIdentity = senderIdentity(conversation, replyingBotName);
        return bot.chatStream(conversation.providerId(), conversation.messages(), conversationId, result -> {
            conversation.messages().add(ChatMessage.of(replyingBotName, result.assistantContent()));
            conversationRepository.save(conversation);
        }, senderIdentity);
    }

    static String getOtherParticipant(Conversation conversation, String one) {
        if (one == null || conversation.participant1() == null || conversation.participant2() == null) return null;
        return one.equals(conversation.participant1()) ? conversation.participant2() : conversation.participant1();
    }

    private List<FileAttachment> resolveFiles(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return List.of();
        return fileIds.stream()
                .map(id -> {
                    try { return fileStorageService.getAttachment(id); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private static String senderIdentity(Conversation conversation, String replyingBotName) {
        if (!conversation.isDirect() || replyingBotName == null) return null;
        var other = getOtherParticipant(conversation, replyingBotName);
        return other == null || "user".equalsIgnoreCase(other) ? null : other;
    }

    public Conversation get(String conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
    }

    public List<Conversation> listAll() {
        return conversationRepository.findAll();
    }

    public List<Conversation> listForParticipant(String participantId) {
        return conversationRepository.findAllForParticipant(participantId);
    }

    public void delete(String conversationId) {
        conversationRepository.deleteById(conversationId);
    }
}
