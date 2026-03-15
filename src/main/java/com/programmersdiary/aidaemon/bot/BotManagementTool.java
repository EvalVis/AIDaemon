package com.programmersdiary.aidaemon.bot;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class BotManagementTool {

    private final BotService botService;

    public BotManagementTool(BotService botService) {
        this.botService = botService;
    }

    @Tool(description = "Create a new bot with a given name and soul (personality description). The soul defines the bot's character, expertise, and behaviour.")
    public String createBot(
            @ToolParam(description = "Unique name for the bot (no spaces, no slashes)") String name,
            @ToolParam(description = "Soul description — the bot's personality, role, and instructions") String soul) {
        try {
            var bot = botService.create(name, soul);
            return "Bot '" + bot.name() + "' created successfully.";
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
