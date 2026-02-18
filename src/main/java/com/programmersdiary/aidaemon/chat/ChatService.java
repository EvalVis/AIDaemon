package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.provider.ChatModelFactory;
import com.programmersdiary.aidaemon.provider.ProviderConfigRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatService {

    private final ProviderConfigRepository configRepository;
    private final ChatModelFactory chatModelFactory;

    public ChatService(ProviderConfigRepository configRepository, ChatModelFactory chatModelFactory) {
        this.configRepository = configRepository;
        this.chatModelFactory = chatModelFactory;
    }

    public String chat(String providerId, List<ChatMessage> messages) {
        var config = configRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        var chatModel = chatModelFactory.create(config);

        var springMessages = messages.stream()
                .map(this::toSpringMessage)
                .toList();

        var response = chatModel.call(new Prompt(springMessages));
        return response.getResult().getOutput().getText();
    }

    private Message toSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(message.content());
            default -> new UserMessage(message.content());
        };
    }
}
