package com.programmersdiary.aidaemon.chat;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationService {

    private final ChatService chatService;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public ConversationService(ChatService chatService) {
        this.chatService = chatService;
    }

    public Conversation create(String providerId) {
        var conversation = new Conversation(UUID.randomUUID().toString(), providerId, new ArrayList<>());
        conversations.put(conversation.id(), conversation);
        return conversation;
    }

    public String sendMessage(String conversationId, String userMessage) {
        var conversation = conversations.get(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found: " + conversationId);
        }
        conversation.messages().add(new ChatMessage("user", userMessage));
        var response = chatService.chat(conversation.providerId(), conversation.messages());
        conversation.messages().add(new ChatMessage("assistant", response));
        return response;
    }

    public Conversation get(String conversationId) {
        var conversation = conversations.get(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found: " + conversationId);
        }
        return conversation;
    }

    public List<Conversation> listAll() {
        return List.copyOf(conversations.values());
    }

    public void delete(String conversationId) {
        conversations.remove(conversationId);
    }

}
