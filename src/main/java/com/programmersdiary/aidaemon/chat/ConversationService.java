package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.delegation.DelegationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ObjectProvider<DelegationService> delegationServiceProvider;
    private final BotService botService;

    public ConversationService(ConversationRepository conversationRepository,
                               ObjectProvider<DelegationService> delegationServiceProvider,
                               BotService botService) {
        this.conversationRepository = conversationRepository;
        this.delegationServiceProvider = delegationServiceProvider;
        this.botService = botService;
    }

    public Conversation create(String name, String providerId) {
        return create(name, providerId, null, null);
    }

    public Conversation create(String name, String providerId, String parentConversationId) {
        return create(name, providerId, null, parentConversationId);
    }

    public Conversation create(String name, String providerId, String botName, String parentConversationId) {
        var conversation = new Conversation(
                UUID.randomUUID().toString(), name, providerId, botName, new ArrayList<>(), parentConversationId,
                System.currentTimeMillis(), null, null);
        return conversationRepository.save(conversation);
    }

    public String sendMessageBotToBot(String callerBotName, String targetBotName, String message, String providerId) {
        if (callerBotName == null || callerBotName.isBlank() || "default".equalsIgnoreCase(callerBotName)) {
            throw new IllegalArgumentException("Bot-to-bot messaging requires a named caller bot");
        }
        if (targetBotName == null || targetBotName.isBlank() || "default".equalsIgnoreCase(targetBotName)) {
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
        if (botName == null || botName.isBlank() || "default".equalsIgnoreCase(botName)) {
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
                                conv.messages(), conv.parentConversationId(), conv.createdAtMillis(),
                                conv.participant1(), conv.participant2());
                        conversationRepository.save(updated);
                        return updated;
                    }
                    return conv;
                })
                .orElseGet(() -> {
                    var name = userParticipantId + " & " + botName;
                    var conv = new Conversation(id, name, providerId, botName, new ArrayList<>(), null,
                            System.currentTimeMillis(), p1, p2);
                    return conversationRepository.save(conv);
                });
    }

    public Conversation updateProvider(String conversationId, String providerId) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        var updated = new Conversation(conversation.id(), conversation.name(), providerId, conversation.botName(),
                conversation.messages(), conversation.parentConversationId(), conversation.createdAtMillis());
        return conversationRepository.save(updated);
    }

    public void save(Conversation conversation) {
        conversationRepository.save(conversation);
    }

    public String sendMessage(String conversationId, String userMessage) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            throw new IllegalArgumentException("No agent selected for this conversation");
        }
        conversation.messages().add(ChatMessage.of("user", userMessage));
        var bot = botService.getBot(conversation.botName());
        var result = bot.chat(conversation.providerId(), conversation.messages(), conversationId);
        conversation.messages().add(ChatMessage.of("assistant", result.assistantContent()));
        conversationRepository.save(conversation);

        if (!result.pendingSubConversationIds().isEmpty()) {
            var delegationService = delegationServiceProvider.getIfAvailable();
            if (delegationService != null) {
                delegationService.startSubAgents(result.pendingSubConversationIds());
            }
        }

        return result.response();
    }

    public Flux<StreamChunk> sendMessageStream(String conversationId, String userMessage) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        if (conversation.providerId() == null || conversation.providerId().isBlank()) {
            return Flux.error(new IllegalArgumentException("No agent selected for this conversation"));
        }
        conversation.messages().add(ChatMessage.of("user", userMessage));
        conversationRepository.save(conversation);

        var bot = botService.getBot(conversation.botName());
        return bot.chatStream(conversation.providerId(), conversation.messages(), conversationId, result -> {
            conversation.messages().add(ChatMessage.of("assistant", result.assistantContent()));
            conversationRepository.save(conversation);
            if (!result.pendingSubConversationIds().isEmpty()) {
                var delegationService = delegationServiceProvider.getIfAvailable();
                if (delegationService != null) {
                    delegationService.startSubAgents(result.pendingSubConversationIds());
                }
            }
        });
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
