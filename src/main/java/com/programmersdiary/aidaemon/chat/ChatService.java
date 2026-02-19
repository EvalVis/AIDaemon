package com.programmersdiary.aidaemon.chat;

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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final ProviderConfigRepository configRepository;
    private final ChatModelFactory chatModelFactory;
    private final SkillsService skillsService;
    private final ScheduledJobExecutor jobExecutor;
    private final ShellAccessService shellAccessService;
    private final McpService mcpService;
    private final String systemInstructions;

    public ChatService(ProviderConfigRepository configRepository,
                       ChatModelFactory chatModelFactory,
                       SkillsService skillsService,
                       ScheduledJobExecutor jobExecutor,
                       ShellAccessService shellAccessService,
                       McpService mcpService,
                       @Value("${aidaemon.system-instructions:}") String systemInstructions) {
        this.configRepository = configRepository;
        this.chatModelFactory = chatModelFactory;
        this.skillsService = skillsService;
        this.jobExecutor = jobExecutor;
        this.shellAccessService = shellAccessService;
        this.mcpService = mcpService;
        this.systemInstructions = systemInstructions;
    }

    public ChatResult chat(String providerId, List<ChatMessage> messages) {
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
        var chatModel = chatModelFactory.create(config, loggingTools);

        var springMessages = new ArrayList<Message>();
        springMessages.add(new SystemMessage(buildSystemContext()));
        springMessages.addAll(messages.stream()
                .filter(m -> !"tool".equals(m.role()))
                .map(this::toSpringMessage)
                .toList());

        var response = chatModel.call(new Prompt(springMessages));
        return new ChatResult(response.getResult().getOutput().getText(), toolLog);
    }

    private String buildSystemContext() {
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
