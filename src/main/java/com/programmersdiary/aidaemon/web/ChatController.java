package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.chat.ChatRequest;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.StreamRequestMetadata;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final BotService botService;

    public ChatController(ChatService chatService, BotService botService) {
        this.chatService = chatService;
        this.botService = botService;
    }

    @PostMapping("/{providerId}")
    public Map<String, String> chat(@PathVariable String providerId, @RequestBody ChatRequest request) {
        var messages = request.messages();
        var bot = botService.getBot(null);
        var meta = bot.streamRequestMetadata(messages, null);
        var contextMessages = bot.buildContext(messages);
        var result = chatService.streamAndCollect(providerId, contextMessages, meta);
        return Map.of("response", result.response());
    }
}
