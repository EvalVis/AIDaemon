package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

public class ConversationManagementTool {

    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final BotRepository botRepository;
    private final String currentBotName;
    private final String currentProviderId;

    public ConversationManagementTool(ConversationService conversationService,
                                      ConversationRepository conversationRepository,
                                      BotRepository botRepository,
                                      String currentBotName,
                                      String currentProviderId) {
        this.conversationService = conversationService;
        this.conversationRepository = conversationRepository;
        this.botRepository = botRepository;
        this.currentBotName = currentBotName;
        this.currentProviderId = currentProviderId;
    }

    @Tool(description = "List all available participants you can invite to conversations: 'user' (the human) and other bot names.")
    public String listAvailableParticipants() {
        var participants = new ArrayList<String>();
        participants.add("user");
        botRepository.findAllNames().stream()
                .filter(n -> !currentBotName.equals(n))
                .forEach(participants::add);
        return participants.isEmpty() ? "No other participants available."
                : "Available participants: " + String.join(", ", participants);
    }

    @Tool(description = "List all conversations you are a participant in.")
    public String listMyConversations() {
        var convs = conversationRepository.findAllForParticipant(currentBotName);
        if (convs.isEmpty()) return "You are not in any conversations.";
        var sb = new StringBuilder("Your conversations:\n");
        for (var c : convs) {
            sb.append("- id: ").append(c.id())
                    .append(", name: ").append(c.name())
                    .append(", participants: ").append(c.participants())
                    .append(", messages: ").append(c.messages().size())
                    .append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Create a new group conversation with specified participants. Returns the conversation ID — save it to memory for future reference.")
    public String createConversation(
            @ToolParam(description = "Name for the conversation") String name,
            @ToolParam(description = "List of participants to invite, e.g. ['user', 'botName']. You are automatically included.") List<String> participants) {
        try {
            var allParticipants = new ArrayList<>(participants != null ? participants : List.of());
            if (!allParticipants.contains(currentBotName)) {
                allParticipants.add(0, currentBotName);
            }
            var conv = conversationService.createConversation(name, currentProviderId, allParticipants);
            return "Conversation '" + name + "' created. ID: " + conv.id() + ". Participants: " + conv.participants();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Add a participant to an existing conversation.")
    public String addParticipant(
            @ToolParam(description = "The conversation ID") String conversationId,
            @ToolParam(description = "Participant to add: 'user' or a bot name") String participantName) {
        try {
            conversationService.addParticipant(conversationId, participantName);
            return participantName + " added to conversation " + conversationId;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = """
            Write a message to a conversation and specify which bot participants to wake up (trigger async response). \
            Use an empty list to just write without triggering anyone. \
            The conversation ID must be one you are a participant in. \
            Save conversation IDs to memory so you can reference them later.""")
    public String writeToConversation(
            @ToolParam(description = "The conversation ID") String conversationId,
            @ToolParam(description = "Your message") String message,
            @ToolParam(description = "Bot names to wake up (trigger async response). Use [] to write without triggering.") List<String> notifyParticipants) {
        try {
            var conv = conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
            conv.messages().add(ChatMessage.of(currentBotName, message));
            conversationService.save(conv);
            var toNotify = notifyParticipants != null ? notifyParticipants : List.<String>of();
            for (var botName : toNotify) {
                conversationService.triggerBotReplyAsync(conversationId, botName);
            }
            if (toNotify.isEmpty()) {
                return "Message written to conversation " + conversationId + ".";
            }
            return "Message written to conversation " + conversationId + ". Notified: " + toNotify + ". Awaiting responses.";
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }
}
