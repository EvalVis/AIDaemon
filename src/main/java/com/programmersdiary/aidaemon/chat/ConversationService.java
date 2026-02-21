package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.delegation.DelegationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ChatService chatService;
    private final ConversationRepository conversationRepository;
    private final ObjectProvider<DelegationService> delegationServiceProvider;

    public ConversationService(ChatService chatService,
                               ConversationRepository conversationRepository,
                               ObjectProvider<DelegationService> delegationServiceProvider) {
        this.chatService = chatService;
        this.conversationRepository = conversationRepository;
        this.delegationServiceProvider = delegationServiceProvider;
    }

    public Conversation create(String name, String providerId) {
        return create(name, providerId, null);
    }

    public Conversation create(String name, String providerId, String parentConversationId) {
        var conversation = new Conversation(
                UUID.randomUUID().toString(), name, providerId, new ArrayList<>(), parentConversationId);
        return conversationRepository.save(conversation);
    }

    public String sendMessage(String conversationId, String userMessage) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        conversation.messages().add(new ChatMessage("user", userMessage));
        var result = chatService.chat(conversation.providerId(), conversation.messages(), conversationId);
        conversation.messages().addAll(result.toolMessages());
        conversation.messages().add(new ChatMessage("assistant", result.response()));
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
        conversation.messages().add(new ChatMessage("user", userMessage));
        conversationRepository.save(conversation);

        return chatService.stream(conversation.providerId(), conversation.messages(), conversationId, result -> {
            conversation.messages().addAll(result.toolMessages());
            conversation.messages().add(new ChatMessage("assistant", result.response()));
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
