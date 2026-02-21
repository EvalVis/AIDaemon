package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.delegation.DelegationTools;
import com.programmersdiary.aidaemon.mcp.McpService;
import com.programmersdiary.aidaemon.provider.ChatModelFactory;
import com.programmersdiary.aidaemon.provider.ProviderConfigRepository;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import com.programmersdiary.aidaemon.skills.ChatTools;
import com.programmersdiary.aidaemon.skills.ShellAccessService;
import com.programmersdiary.aidaemon.skills.ShellTool;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@Service
public class ChatService {

    private final ProviderConfigRepository configRepository;
    private final ChatModelFactory chatModelFactory;
    private final SkillsService skillsService;
    private final ScheduledJobExecutor jobExecutor;
    private final ShellAccessService shellAccessService;
    private final McpService mcpService;
    private final ConversationRepository conversationRepository;
    private final String systemInstructions;
    private final boolean delegationEnabled;

    public ChatService(ProviderConfigRepository configRepository,
                       ChatModelFactory chatModelFactory,
                       SkillsService skillsService,
                       ScheduledJobExecutor jobExecutor,
                       ShellAccessService shellAccessService,
                       McpService mcpService,
                       ConversationRepository conversationRepository,
                       @Value("${aidaemon.system-instructions:}") String systemInstructions,
                       @Value("${aidaemon.delegation-enabled:false}") boolean delegationEnabled,
                       @Value("${aidaemon.delegation-threshold-seconds:30}") int delegationThresholdSeconds) {
        this.configRepository = configRepository;
        this.chatModelFactory = chatModelFactory;
        this.skillsService = skillsService;
        this.jobExecutor = jobExecutor;
        this.shellAccessService = shellAccessService;
        this.mcpService = mcpService;
        this.conversationRepository = conversationRepository;
        this.systemInstructions = systemInstructions
                .replace("{threshold}", String.valueOf(delegationThresholdSeconds));
        this.delegationEnabled = delegationEnabled;
    }

    public ChatResult chat(String providerId, List<ChatMessage> messages) {
        return chat(providerId, messages, null);
    }

    public ChatResult chat(String providerId, List<ChatMessage> messages, String conversationId) {
        var config = configRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        var toolLog = new ArrayList<ChatMessage>();
        var loggingTools = new ArrayList<ToolCallback>();
        Arrays.asList(ToolCallbacks.from(new ChatTools(skillsService, jobExecutor, providerId)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        Arrays.asList(ToolCallbacks.from(new ShellTool(shellAccessService)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        mcpService.getToolCallbacksByServer().forEach((serverName, callbacks) ->
                callbacks.forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, serverName))));

        DelegationTools delegationTools = null;
        boolean isSubConversation = false;
        if (delegationEnabled && conversationId != null) {
            var conversation = conversationRepository.findById(conversationId).orElse(null);
            isSubConversation = conversation != null && conversation.parentConversationId() != null;
            delegationTools = new DelegationTools(conversationRepository, conversationId, providerId);
            Arrays.asList(ToolCallbacks.from(delegationTools))
                    .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        }

        var chatModel = chatModelFactory.create(config, loggingTools);

        var springMessages = new ArrayList<Message>();
        springMessages.add(new SystemMessage(buildSystemContext(isSubConversation)));
        springMessages.addAll(messages.stream()
                .filter(m -> !"tool".equals(m.role()))
                .map(this::toSpringMessage)
                .toList());

        try {
            var response = chatModel.call(new Prompt(springMessages));
            var pendingIds = delegationTools != null
                    ? delegationTools.getPendingSubConversationIds() : List.<String>of();
            return new ChatResult(response.getResult().getOutput().getText(), toolLog, pendingIds);
        } catch (Exception e) {
            return new ChatResult("[Error] " + e.getMessage(), toolLog, List.of());
        }
    }

    public Flux<StreamChunk> stream(String providerId, List<ChatMessage> messages, String conversationId,
                                   Consumer<ChatResult> onComplete) {
        var config = configRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        var toolLog = new ArrayList<ChatMessage>();
        var loggingTools = new ArrayList<ToolCallback>();
        Arrays.asList(ToolCallbacks.from(new ChatTools(skillsService, jobExecutor, providerId)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        Arrays.asList(ToolCallbacks.from(new ShellTool(shellAccessService)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        mcpService.getToolCallbacksByServer().forEach((serverName, callbacks) ->
                callbacks.forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, serverName))));

        DelegationTools delegationTools = null;
        boolean isSubConversation = false;
        if (delegationEnabled && conversationId != null) {
            var conversation = conversationRepository.findById(conversationId).orElse(null);
            isSubConversation = conversation != null && conversation.parentConversationId() != null;
            delegationTools = new DelegationTools(conversationRepository, conversationId, providerId);
            Arrays.asList(ToolCallbacks.from(delegationTools))
                    .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        }

        var chatModel = chatModelFactory.create(config, loggingTools);
        if (!(chatModel instanceof StreamingChatModel streamingModel)) {
            var result = chat(providerId, messages, conversationId);
            onComplete.accept(result);
            return Flux.just(new StreamChunk(StreamChunk.TYPE_ANSWER, result.response()));
        }

        var springMessages = new ArrayList<Message>();
        springMessages.add(new SystemMessage(buildSystemContext(isSubConversation)));
        springMessages.add(new SystemMessage("The remaining text is the conversation history. User might refer to something in it."));
        springMessages.addAll(messages.stream()
                .filter(m -> !"tool".equals(m.role()))
                .map(this::toSpringMessage)
                .toList());

        var pendingIds = delegationTools != null
                ? delegationTools.getPendingSubConversationIds() : List.<String>of();
        var contentAccum = new StringBuilder();
        var reasoningAccum = new StringBuilder();

        return streamingModel.stream(new Prompt(springMessages))
                .flatMap(response -> {
                    var c = toStreamChunk(response);
                    return c != null ? Flux.just(c) : Flux.empty();
                })
                .doOnNext(c -> {
                    if (StreamChunk.TYPE_REASONING.equals(c.type())) {
                        reasoningAccum.append(c.content());
                    } else {
                        contentAccum.append(c.content());
                    }
                })
                .doOnComplete(() -> onComplete.accept(new ChatResult(contentAccum.toString(), toolLog, pendingIds)))
                .doOnError(e -> onComplete.accept(new ChatResult("[Error] " + e.getMessage(), toolLog, List.of())));
    }

    private StreamChunk toStreamChunk(ChatResponse response) {
        var result = response.getResult();
        if (result == null) {
            return null;
        }
        var output = result.getOutput();
        if (output == null) {
            return null;
        }
        var metadata = output.getMetadata();
        if (metadata != null) {
            var reasoning = metadata.get("reasoning");
            if (reasoning != null && !reasoning.toString().isEmpty()) {
                return new StreamChunk(StreamChunk.TYPE_REASONING, reasoning.toString());
            }
            var reasoningContent = metadata.get("reasoning_content");
            if (reasoningContent != null && !reasoningContent.toString().isEmpty()) {
                return new StreamChunk(StreamChunk.TYPE_REASONING, reasoningContent.toString());
            }
        }
        var text = output.getText();
        return new StreamChunk(StreamChunk.TYPE_ANSWER, text != null ? text : "");
    }

    private String buildSystemContext(boolean isSubConversation) {
        var sb = new StringBuilder();

        var memory = skillsService.readMemory();
        if (!memory.isEmpty()) {
            sb.append("You have persistent memory. Current contents:\n");
            memory.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }

        var skills = skillsService.listSkills();
        if (!skills.isEmpty()) {
            sb.append("Installed skills: ").append(String.join(", ", skills)).append("\n");
            var allFiles = skillsService.listAllFiles();
            if (!allFiles.isEmpty()) {
                sb.append("Skill files: ").append(String.join(", ", allFiles)).append("\n");
            }
            sb.append("\n");
        }

        var mcpServers = mcpService.getConnectedServers();
        if (!mcpServers.isEmpty()) {
            sb.append("Connected MCP servers: ").append(String.join(", ", mcpServers)).append("\n");
            sb.append("MCP tools are available for use.\n\n");
        }

        if (isSubConversation) {
            sb.append("You are a sub-agent working on a specific subtask delegated from a parent agent. ")
                .append("Focus entirely on your assigned task and provide a thorough, complete response.");
        }

        if (!systemInstructions.isBlank()) {
            sb.append(systemInstructions);
        }

        return sb.toString();
    }

    private Message toSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(message.content());
            default -> new UserMessage(message.content());
        };
    }

}
