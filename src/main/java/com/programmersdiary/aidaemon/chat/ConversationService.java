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
                System.currentTimeMillis());
        return conversationRepository.save(conversation);
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

    public void delete(String conversationId) {
        conversationRepository.deleteById(conversationId);
    }
}
