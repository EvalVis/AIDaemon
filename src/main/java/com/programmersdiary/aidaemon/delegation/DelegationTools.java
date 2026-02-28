package com.programmersdiary.aidaemon.delegation;

import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.Conversation;
import com.programmersdiary.aidaemon.chat.ConversationRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DelegationTools {

    private final ConversationRepository conversationRepository;
    private final String parentConversationId;
    private final String providerId;
    private final String botName;
    private final List<String> pendingSubConversationIds = new ArrayList<>();

    public DelegationTools(ConversationRepository conversationRepository,
                           String parentConversationId,
                           String providerId,
                           String botName) {
        this.conversationRepository = conversationRepository;
        this.parentConversationId = parentConversationId;
        this.providerId = providerId;
        this.botName = botName;
    }

    @Tool(description = "Delegate a subproblem to a sub-agent. Creates a sub-conversation and sends the instruction. " +
            "The sub-agent will process it asynchronously after you finish your current response.")
    public String delegateToSubAgent(
            @ToolParam(description = "Short descriptive name for the sub-task") String name,
            @ToolParam(description = "Clear, self-contained instruction for the sub-agent to solve") String instruction) {
        var subConversation = new Conversation(
                UUID.randomUUID().toString(), name, providerId, botName,
                new ArrayList<>(List.of(ChatMessage.of("user", instruction))),
                parentConversationId, System.currentTimeMillis(), null, null, null);
        conversationRepository.save(subConversation);
        pendingSubConversationIds.add(subConversation.id());
        return "Delegated to sub-agent '" + name + "' (" + subConversation.id() + "). " +
                "It will be processed after you finish your response.";
    }

    @Tool(description = "Send additional instructions to an existing sub-agent whose work needs revision. " +
            "Use this after reviewing a sub-agent's completed work in a [Delegation Status Update].")
    public String addWorkToSubAgent(
            @ToolParam(description = "The sub-conversation ID from the delegation status update") String subConversationId,
            @ToolParam(description = "Additional instructions or revision requests for the sub-agent") String instruction) {
        var sub = conversationRepository.findById(subConversationId).orElse(null);
        if (sub == null) {
            return "Error: Sub-conversation not found: " + subConversationId;
        }
        if (!parentConversationId.equals(sub.parentConversationId())) {
            return "Error: Sub-conversation does not belong to the current conversation.";
        }
        sub.messages().add(ChatMessage.of("user", instruction));
        conversationRepository.save(sub);
        pendingSubConversationIds.add(subConversationId);
        return "Additional work sent to sub-agent '" + sub.name() + "' (" + subConversationId + "). " +
                "It will be processed after you finish your response.";
    }

    public List<String> getPendingSubConversationIds() {
        return List.copyOf(pendingSubConversationIds);
    }
}
