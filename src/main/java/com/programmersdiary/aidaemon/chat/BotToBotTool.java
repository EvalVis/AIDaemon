package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;

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

    @Tool(description = "List participants you can message: 'user' (the human) and other bots.")
    public String listOtherBots() {
        var participants = new ArrayList<String>();
        participants.add("user");
        botRepository.findAllNames().stream()
                .filter(n -> !currentBotName.equals(n))
                .forEach(participants::add);
        return participants.isEmpty() ? "No other participants found."
                : "Participants: " + String.join(", ", participants);
    }

    @Tool(description = """
            Send a message to a participant.
            Use 'user' to write into the human's conversation with you.
            Use a bot name to send an async message to another bot — the bot will respond in its own time. \
            You will be notified when you get a reply.""")
    public String messageBot(
            @ToolParam(description = "Target participant: 'user' or a bot name") String targetName,
            @ToolParam(description = "The message to send") String message) {
        if (currentProviderId == null || currentProviderId.isBlank()) {
            return "No provider selected; cannot send message.";
        }
        try {
            return conversationService.sendMessageToParticipant(
                    currentBotName, targetName.trim(), message != null ? message.trim() : "", currentProviderId);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
