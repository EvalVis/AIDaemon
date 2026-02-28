package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class BotToBotTool {

    private final ConversationService conversationService;
    private final BotRepository botRepository;
    private final String currentProviderId;
    private final String currentBotName;

    public BotToBotTool(ConversationService conversationService, BotRepository botRepository,
                        String currentProviderId, String currentBotName) {
        this.conversationService = conversationService;
        this.botRepository = botRepository;
        this.currentProviderId = currentProviderId;
        this.currentBotName = currentBotName;
    }

    @Tool(description = "List other bots you can message. Returns bot names (excluding yourself).")
    public String listOtherBots() {
        var others = botRepository.findAllNames().stream()
                .filter(n -> !currentBotName.equals(n))
                .toList();
        return others.isEmpty() ? "No bots found." : "Other bots: " + String.join(", ", others);
    }

    @Tool(description = "Send a message to another bot and get its reply. Use when you need to ask another bot for information or to perform a task. The other bot will respond in the same provider context.")
    public String messageBot(
            @ToolParam(description = "Name of the bot to message (e.g. trickster, maverick)") String targetBotName,
            @ToolParam(description = "The message or question to send to the other bot") String message) {
        if (currentBotName == null || currentBotName.isBlank() || "default".equalsIgnoreCase(currentBotName)) {
            return "Bot-to-bot messaging is only available for named bots.";
        }
        if (currentProviderId == null || currentProviderId.isBlank()) {
            return "No provider selected; cannot message another bot.";
        }
        try {
            var response = conversationService.sendMessageBotToBot(
                    currentBotName, targetBotName.trim(), message != null ? message.trim() : "", currentProviderId);
            return response != null ? response : "(no response)";
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
