package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Conversation(String id,
                           String name,
                           String providerId,
                           List<ChatMessage> messages,
                           Long createdAtMillis,
                           List<String> participants) {

    public Conversation(String id, String name, String providerId, List<ChatMessage> messages,
                        Long createdAtMillis) {
        this(id, name, providerId, messages, createdAtMillis, null);
    }
}
