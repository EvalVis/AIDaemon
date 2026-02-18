package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.context.ChatTools;
import com.programmersdiary.aidaemon.context.ContextService;
import com.programmersdiary.aidaemon.provider.ChatModelFactory;
import com.programmersdiary.aidaemon.provider.ProviderConfigRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ProviderConfigRepository configRepository;
    private final ChatModelFactory chatModelFactory;
    private final ContextService contextService;

    public ChatService(ProviderConfigRepository configRepository,
                       ChatModelFactory chatModelFactory,
                       ContextService contextService) {
        this.configRepository = configRepository;
        this.chatModelFactory = chatModelFactory;
        this.contextService = contextService;
    }

    public String chat(String providerId, List<ChatMessage> messages) {
        var config = configRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        var tools = Arrays.asList(ToolCallbacks.from(new ChatTools(contextService)));
        var chatModel = chatModelFactory.create(config, tools);

        var springMessages = new ArrayList<Message>();
        springMessages.add(new SystemMessage(buildSystemContext()));
        springMessages.addAll(messages.stream()
                .map(this::toSpringMessage)
                .toList());

        var response = chatModel.call(new Prompt(springMessages));
        return response.getResult().getOutput().getText();
    }

    private String buildSystemContext() {
        var sb = new StringBuilder();

        var memory = contextService.readMemory();
        if (!memory.isEmpty()) {
            sb.append("You have persistent memory. Current contents:\n");
            memory.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }

        var files = contextService.listFiles();
        if (!files.isEmpty()) {
            sb.append("Files available in context folder: ");
            sb.append(files.stream().collect(Collectors.joining(", ")));
            sb.append("\n\n");
        }

        sb.append("Use the saveMemory tool when the user asks you to remember something. ");
        sb.append("Use readContextFile/listContextFiles tools to access files in the context folder.");

        return sb.toString();
    }

    private Message toSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(message.content());
            default -> new UserMessage(message.content());
        };
    }
}
