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

    public String sendMessageBotToBot(String callerBotName, String targetBotName, String message, String providerId) {
        if (callerBotName == null || callerBotName.isBlank()) {
            throw new IllegalArgumentException("Bot-to-bot messaging requires a named caller bot");
        }
        if (targetBotName == null || targetBotName.isBlank()) {
            throw new IllegalArgumentException("Target bot name is required");
        }
        if (callerBotName.equals(targetBotName)) {
            throw new IllegalArgumentException("Caller and target bot must be different");
        }
        var botNames = botService.listBots().stream().map(b -> b.name()).toList();
        if (!botNames.contains(callerBotName)) {
            throw new IllegalArgumentException("Caller bot not found: " + callerBotName);
        }
        if (!botNames.contains(targetBotName)) {
            throw new IllegalArgumentException("Target bot not found: " + targetBotName);
        }
        var conv = getOrCreateDirect(callerBotName, targetBotName, providerId);
        return sendMessage(conv.id(), message);
    }

    public Conversation getOrCreateDirect(String userParticipantId, String botName, String providerId) {
        if (botName == null || botName.isBlank()) {
            throw new IllegalArgumentException("Direct chat requires a named bot");
        }
        if (!botService.listBots().stream().anyMatch(b -> botName.equals(b.name()))) {
            throw new IllegalArgumentException("Bot not found: " + botName);
        }
        if (!"user".equals(userParticipantId) && !botService.listBots().stream().anyMatch(b -> userParticipantId.equals(b.name()))) {
            throw new IllegalArgumentException("Participant not found: " + userParticipantId);
        }
        var id = Conversation.canonicalId(userParticipantId, botName);
        var p1 = userParticipantId.compareTo(botName) <= 0 ? userParticipantId : botName;
        var p2 = userParticipantId.compareTo(botName) <= 0 ? botName : userParticipantId;
        return conversationRepository.findById(id)
                .map(conv -> {
                    if (providerId != null && !providerId.isBlank() && !providerId.equals(conv.providerId())) {
                        var updated = new Conversation(conv.id(), conv.name(), providerId, conv.botName(),
                                conv.messages(), conv.createdAtMillis(),
                                conv.participant1(), conv.participant2(), conv.direct());
                        conversationRepository.save(updated);
                        return updated;
                    }
                    return conv;
                })
                .orElseGet(() -> {
                    var name = userParticipantId + " & " + botName;
                    var conv = new Conversation(id, name, providerId, botName, new ArrayList<>(),
                            System.currentTimeMillis(), p1, p2, true);
                    return conversationRepository.save(conv);
                });
    }

    public void save(Conversation conversation) {
        conversationRepository.save(conversation);
    }

    public String sendMessage(String conversationId, String userMessage) {
        return sendMessage(conversationId, userMessage, List.of());
    }

    public String sendMessage(String conversationId, String userMessage, List<String> fileIds) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            throw new IllegalArgumentException("No agent selected for this conversation");
        }
        var files = resolveFiles(fileIds);
        conversation.messages().add(ChatMessage.ofWithFiles("user", userMessage, files));
        var bot = botService.getBot(conversation.botName());
        var senderIdentity = senderIdentity(conversation);
        var result = bot.chat(conversation.providerId(), conversation.messages(), conversationId, senderIdentity);
        conversation.messages().add(ChatMessage.of("assistant", result.assistantContent()));
        conversationRepository.save(conversation);
        return result.response();
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage) {
        return sendMessageStream(conversationId, userMessage, List.of());
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage, List<String> fileIds) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            return Flux.error(new IllegalArgumentException("No agent selected for this conversation"));
        }
        var files = resolveFiles(fileIds);
        conversation.messages().add(ChatMessage.ofWithFiles("user", userMessage, files));
        conversationRepository.save(conversation);

        var bot = botService.getBot(conversation.botName());
        var senderIdentity = senderIdentity(conversation);
        return bot.chatStream(conversation.providerId(), conversation.messages(), conversationId, result -> {
            conversation.messages().add(ChatMessage.of("assistant", result.assistantContent()));
            conversationRepository.save(conversation);
        }, senderIdentity);
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

    private static String senderIdentity(Conversation conversation) {
        if (!conversation.isDirect() || conversation.botName() == null) return null;
        var p1 = conversation.participant1();
        var p2 = conversation.participant2();
        if (p1 == null || p2 == null) return null;
        var other = p1.equals(conversation.botName()) ? p2 : p1;
        return "user".equalsIgnoreCase(other) ? null : other;
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
