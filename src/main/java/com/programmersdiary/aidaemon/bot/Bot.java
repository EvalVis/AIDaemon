package com.programmersdiary.aidaemon.bot;

import com.programmersdiary.aidaemon.chat.ChatContextBuilder;
import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.chat.StreamRequestMetadata;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public class Bot {

    private final String name;
    private final BotService botService;
    private final ChatContextBuilder contextBuilder;
    private final ContextConfig contextConfig;

    Bot(String name, BotService botService, ChatContextBuilder contextBuilder, ContextConfig contextConfig) {
        this.name = name;
        this.botService = botService;
        this.contextBuilder = contextBuilder;
        this.contextConfig = contextConfig;
    }

    public String name() {
        return name;
    }

    public List<Message> buildContext(List<ChatMessage> messages) {
        var named = isNamed();
        var convLimit = contextConfig.conversationLimit(named);
        var personalLimit = contextConfig.personalMemoryLimit(named);
        return contextBuilder.buildMessages(messages, name, convLimit, personalLimit, contextConfig.systemInstructions());
    }

    public StreamRequestMetadata streamRequestMetadata(List<ChatMessage> messages, String conversationId) {
        return new StreamRequestMetadata(messages, conversationId, name, contextConfig.conversationLimit(isNamed()));
    }

    public void appendToPersonalMemory(String userContent, List<ChatMessage> toolMessages, String assistantContent) {
        botService.appendTurnToPersonalMemory(name, userContent, toolMessages, assistantContent);
    }

    private boolean isNamed() {
        return name != null && !name.isBlank() && !"default".equalsIgnoreCase(name);
    }
}
