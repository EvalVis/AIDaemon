package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.delegation.DelegationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
                UUID.randomUUID().toString(), name, providerId, new ArrayList<>(), parentConversationId,
                System.currentTimeMillis());
        return conversationRepository.save(conversation);
    }

    public Conversation updateProvider(String conversationId, String providerId) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        var updated = new Conversation(conversation.id(), conversation.name(), providerId, conversation.messages(),
                conversation.parentConversationId(), conversation.createdAtMillis());
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
        var result = chatService.streamAndCollect(conversation.providerId(), conversation.messages(), conversationId);
        if (result.orderedParts() != null && !result.orderedParts().isEmpty()) {
            conversation.messages().add(ChatMessage.of("assistant", toPartsJson(result.orderedParts())));
        } else {
            conversation.messages().add(ChatMessage.of("assistant", result.response()));
        }
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

        return chatService.stream(conversation.providerId(), conversation.messages(), conversationId, result -> {
            if (result.orderedParts() != null && !result.orderedParts().isEmpty()) {
                conversation.messages().add(ChatMessage.of("assistant", toPartsJson(result.orderedParts())));
            } else {
                conversation.messages().add(ChatMessage.of("assistant", result.response()));
            }
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

    private static String toPartsJson(List<StreamChunk> orderedParts) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("parts", orderedParts));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
