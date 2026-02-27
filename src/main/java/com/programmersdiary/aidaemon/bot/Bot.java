package com.programmersdiary.aidaemon.bot;

import com.programmersdiary.aidaemon.chat.ChatContextBuilder;
import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatResult;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.chat.ContextWindowTrimmer;
import com.programmersdiary.aidaemon.chat.StreamChunk;
import com.programmersdiary.aidaemon.chat.StreamRequestMetadata;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class Bot {

    private final String name;
    private final BotService botService;
    private final ChatContextBuilder contextBuilder;
    private final ContextConfig contextConfig;
    private final ChatService chatService;

    Bot(String name, BotService botService, ContextConfig contextConfig, ChatService chatService,
        SkillsService skillsService) {
        this.name = name;
        this.botService = botService;
        this.contextBuilder = new ChatContextBuilder(botService, skillsService);
        this.contextConfig = contextConfig;
        this.chatService = chatService;
    }

    public String name() {
        return name;
    }

    public ChatResult chat(String providerId, List<ChatMessage> messages, String conversationId) {
        var meta = streamRequestMetadata(messages, conversationId);
        var contextMessages = buildContext(messages);
        var result = chatService.streamAndCollect(providerId, contextMessages, meta);
        appendToPersonalMemoryAfterChat(messages, result);
        return result;
    }

    public Flux<StreamChunk> chatStream(String providerId, List<ChatMessage> messages, String conversationId,
                                       Consumer<ChatResult> onComplete) {
        var meta = streamRequestMetadata(messages, conversationId);
        var contextMessages = buildContext(messages);
        return chatService.stream(providerId, contextMessages, meta, result -> {
            appendToPersonalMemoryAfterChat(messages, result);
            onComplete.accept(result);
        });
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

    public void appendToPersonalMemory(List<ChatMessage> contextConversation, String assistantContent) {
        botService.appendTurnToPersonalMemory(name, contextConversation, assistantContent);
    }

    private void appendToPersonalMemoryAfterChat(List<ChatMessage> messages, ChatResult result) {
        var filtered = messages.stream().filter(m -> !"tool".equals(m.role())).toList();
        if (filtered.isEmpty()) return;
        var history = filtered.subList(0, filtered.size() - 1);
        var convLimit = contextConfig.conversationLimit(isNamed());
        var contextConversation = new ArrayList<ChatMessage>();
        if (!history.isEmpty()) {
            contextConversation.addAll(ContextWindowTrimmer.trimChatHistory(history, convLimit));
        }
        contextConversation.add(filtered.get(filtered.size() - 1));
        appendToPersonalMemory(contextConversation, result.assistantContent());
    }

    private boolean isNamed() {
        return name != null && !name.isBlank() && !"default".equalsIgnoreCase(name);
    }
}
