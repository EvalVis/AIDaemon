package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.chat.ChatContextBuilder;
import com.programmersdiary.aidaemon.chat.ChatRequest;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.chat.StreamRequestMetadata;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatContextBuilder contextBuilder;
    private final ContextConfig contextConfig;

    public ChatController(ChatService chatService, ChatContextBuilder contextBuilder, ContextConfig contextConfig) {
        this.chatService = chatService;
        this.contextBuilder = contextBuilder;
        this.contextConfig = contextConfig;
    }

    @PostMapping("/{providerId}")
    public Map<String, String> chat(@PathVariable String providerId, @RequestBody ChatRequest request) {
        var messages = request.messages();
        var meta = new StreamRequestMetadata(messages, null, null, contextConfig.charsLimit());
        var contextMessages = contextBuilder.buildMessages(messages, null, contextConfig.charsLimit(), 0, contextConfig.systemInstructions());
        var result = chatService.streamAndCollect(providerId, contextMessages, meta);
        return Map.of("response", result.response());
    }
}
