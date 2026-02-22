package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.chat.ChatRequest;
import com.programmersdiary.aidaemon.chat.ChatService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/{providerId}")
    public Map<String, String> chat(@PathVariable String providerId, @RequestBody ChatRequest request) {
        var result = chatService.streamAndCollect(providerId, request.messages());
        return Map.of("response", result.response());
    }
}
