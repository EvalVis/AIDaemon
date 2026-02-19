package com.programmersdiary.aidaemon.chat;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ChatService chatService;
    private final ConversationRepository conversationRepository;

    public ConversationService(ChatService chatService, ConversationRepository conversationRepository) {
        this.chatService = chatService;
        this.conversationRepository = conversationRepository;
    }

    public Conversation create(String name, String providerId) {
        var conversation = new Conversation(UUID.randomUUID().toString(), name, providerId, new ArrayList<>());
        return conversationRepository.save(conversation);
    }

    public String sendMessage(String conversationId, String userMessage) {
        var conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
        conversation.messages().add(new ChatMessage("user", userMessage));
        var result = chatService.chat(conversation.providerId(), conversation.messages());
        conversation.messages().addAll(result.toolMessages());
        conversation.messages().add(new ChatMessage("assistant", result.response()));
        conversationRepository.save(conversation);
        return result.response();
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
