package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final java.util.concurrent.ExecutorService BOT_EXECUTOR = Executors.newCachedThreadPool();

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
                for (var toolMsg : result.toolMessages()) {
                    conversationRepository.addMessage(conversationId, toolMsg);
                }
            } catch (Exception e) {
                log.error("triggerBotReplyAsync failed for bot='{}' conv='{}'", botName, conversationId, e);
            }
        }, BOT_EXECUTOR);
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

    public void sendMessage(String conversationId, String userMessage) {
        sendMessage(conversationId, "user", userMessage, List.of(), List.of());
    }

    public void sendMessage(String conversationId, String userMessage, List<String> fileIds) {
        sendMessage(conversationId, "user", userMessage, fileIds, List.of());
    }

    public void sendMessage(String conversationId, String userMessage,
                            List<String> fileIds, List<String> notifyParticipants) {
        sendMessage(conversationId, "user", userMessage, fileIds, notifyParticipants);
    }

    public void sendMessage(String conversationId, String senderParticipant, String message,
                            List<String> fileIds, List<String> notifyParticipants) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            throw new IllegalArgumentException("No agent selected for this conversation");
        }
        var files = resolveFiles(fileIds);
        conversationRepository.addMessage(conversationId, ChatMessage.ofWithFiles(senderParticipant, message, files));

        var botsToNotify = notifyParticipants != null ? notifyParticipants : List.<String>of();
        for (var botName : botsToNotify) {
            triggerBotReplyAsync(conversationId, botName);
        }
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
