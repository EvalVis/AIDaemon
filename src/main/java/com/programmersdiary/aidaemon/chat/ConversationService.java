package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

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

    public Conversation createConversation(String name, String providerId, List<String> participants) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Conversation name is required");
        }
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("At least one participant is required");
        }
        participants.forEach(this::validateParticipant);
        var conv = new Conversation(UUID.randomUUID().toString(), name, providerId, new ArrayList<>(),
                System.currentTimeMillis(), List.copyOf(participants));
        return conversationRepository.save(conv);
    }

    public Conversation addParticipant(String conversationId, String participantName) {
        validateParticipant(participantName);
        var conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        var current = new ArrayList<>(conv.participants() != null ? conv.participants() : List.of());
        if (!current.contains(participantName)) {
            current.add(participantName);
        }
        var updated = new Conversation(conv.id(), conv.name(), conv.providerId(),
                conv.messages(), conv.createdAtMillis(), List.copyOf(current));
        return conversationRepository.save(updated);
    }

    void triggerBotReplyAsync(String conversationId, String botName) {
        CompletableFuture.runAsync(() -> {
            try {
                var conv = conversationRepository.findById(conversationId).orElse(null);
                if (conv == null || conv.providerId() == null || conv.providerId().isBlank()) return;
                var bot = botService.getBot(botName);
                var senderIdentity = lastBotSenderOf(conv, botName);
                var result = bot.chat(conv.providerId(), conv.messages(), conversationId, senderIdentity);
                conv.messages().add(ChatMessage.of(botName, result.assistantContent()));
                conversationRepository.save(conv);
            } catch (Exception e) {
                log.error("Async bot reply failed for bot '{}' on conversation '{}'", botName, conversationId, e);
            }
        });
    }

    private static String lastBotSenderOf(Conversation conv, String excludeBotName) {
        if (conv.messages() == null) return null;
        for (int i = conv.messages().size() - 1; i >= 0; i--) {
            var p = conv.messages().get(i).participant();
            if (!excludeBotName.equals(p) && !"user".equalsIgnoreCase(p) && !"tool".equalsIgnoreCase(p)) {
                return p;
            }
        }
        return null;
    }

    private void validateParticipant(String participant) {
        if ("user".equalsIgnoreCase(participant)) return;
        if (botService.listBots().stream().noneMatch(b -> participant.equals(b.name()))) {
            throw new IllegalArgumentException("Participant not found: " + participant);
        }
    }

    public void save(Conversation conversation) {
        conversationRepository.save(conversation);
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage) {
        return sendMessageStream(conversationId, userMessage, List.of(), List.of());
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage, List<String> fileIds) {
        return sendMessageStream(conversationId, "user", userMessage, fileIds, List.of());
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage,
                                               List<String> fileIds, List<String> notifyParticipants) {
        return sendMessageStream(conversationId, "user", userMessage, fileIds, notifyParticipants);
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String senderParticipant, String message,
                                               List<String> fileIds, List<String> notifyParticipants) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            return Flux.error(new IllegalArgumentException("No agent selected for this conversation"));
        }
        var files = resolveFiles(fileIds);
        conversation.messages().add(ChatMessage.ofWithFiles(senderParticipant, message, files));
        conversationRepository.save(conversation);

        var botsToNotify = notifyParticipants != null ? notifyParticipants : List.<String>of();

        if (botsToNotify.size() == 1) {
            var replyingBotName = botsToNotify.get(0);
            var bot = botService.getBot(replyingBotName);
            var senderIdentity = "user".equalsIgnoreCase(senderParticipant) ? null : senderParticipant;
            return bot.chatStream(conversation.providerId(), conversation.messages(), conversationId, result -> {
                conversation.messages().add(ChatMessage.of(replyingBotName, result.assistantContent()));
                conversationRepository.save(conversation);
            }, senderIdentity);
        }

        for (var botName : botsToNotify) {
            triggerBotReplyAsync(conversationId, botName);
        }
        return Flux.empty();
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
