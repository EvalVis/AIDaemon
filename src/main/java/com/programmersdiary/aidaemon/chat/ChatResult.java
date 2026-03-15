package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

public record ChatResult(String response, List<StreamChunk> orderedParts, String reasoning,
                         List<ChatMessage> toolMessages) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ChatResult(String response) {
        this(response, null, null, List.of());
    }

    public ChatResult(String response, List<StreamChunk> orderedParts) {
        this(response, orderedParts, null, List.of());
    }

    public ChatResult(String response, List<StreamChunk> orderedParts, String reasoning) {
        this(response, orderedParts, reasoning, List.of());
    }

    public String assistantContent() {
        if (orderedParts != null && !orderedParts.isEmpty()) {
            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of("parts", orderedParts));
            } catch (JsonProcessingException e) {
                return response != null ? response : "";
            }
        }
        return response != null ? response : "";
    }
}
