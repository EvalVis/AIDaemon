package com.programmersdiary.aidaemon.bot;

import com.programmersdiary.aidaemon.chat.ChatContextBuilder;
import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatResult;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.chat.StreamChunk;
import com.programmersdiary.aidaemon.chat.StreamRequestMetadata;
import com.programmersdiary.aidaemon.files.FileStorageService;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

public class Bot {

    private final String name;
    private final BotService botService;
    private final ChatContextBuilder contextBuilder;
    private final ContextConfig contextConfig;
    private final ChatService chatService;

    Bot(String name, BotService botService, ContextConfig contextConfig, ChatService chatService,
        SkillsService skillsService, FileStorageService fileStorageService) {
        this.name = name;
        this.botService = botService;
        this.contextBuilder = new ChatContextBuilder(botService, skillsService, fileStorageService);
        this.contextConfig = contextConfig;
        this.chatService = chatService;
    }

    public String name() {
        return name;
    }

    public ChatResult chat(String providerId, List<ChatMessage> messages, String conversationId, String senderIdentity) {
        var meta = streamRequestMetadata(messages, conversationId);
        var contextMessages = buildContext(messages, senderIdentity);
        return chatService.streamAndCollect(providerId, contextMessages, meta);
    }

    public Flux<StreamChunk> chatStream(String providerId, List<ChatMessage> messages, String conversationId,
                                       Consumer<ChatResult> onComplete, String senderIdentity) {
        var meta = streamRequestMetadata(messages, conversationId);
        var contextMessages = buildContext(messages, senderIdentity);
        return chatService.stream(providerId, contextMessages, meta, onComplete);
    }

    public List<Message> buildContext(List<ChatMessage> messages) {
        return buildContext(messages, null);
    }

    public List<Message> buildContext(List<ChatMessage> messages, String senderIdentity) {
        return contextBuilder.buildMessages(messages, name, contextConfig.charsLimit(),
                contextConfig.systemInstructions(), senderIdentity);
    }

    public StreamRequestMetadata streamRequestMetadata(List<ChatMessage> messages, String conversationId) {
        return new StreamRequestMetadata(messages, conversationId, name, contextConfig.charsLimit());
    }
}
