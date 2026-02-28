package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotRepository;
import com.programmersdiary.aidaemon.mcp.McpService;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import com.programmersdiary.aidaemon.skills.ChatTools;
import com.programmersdiary.aidaemon.skills.ShellAccessService;
import com.programmersdiary.aidaemon.skills.ShellTool;
import com.programmersdiary.aidaemon.skills.SkillsService;
import com.programmersdiary.aidaemon.skills.SmitheryMcpTool;
import com.programmersdiary.aidaemon.skills.SmitherySkillTool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class ChatToolCallbacksService {

    private final SkillsService skillsService;
    private final ScheduledJobExecutor jobExecutor;
    private final ShellAccessService shellAccessService;
    private final ConversationService conversationService;
    private final BotRepository botRepository;
    private final McpService mcpService;
    private final SmitheryMcpTool smitheryMcpTool;
    private final SmitherySkillTool smitherySkillTool;

    public ChatToolCallbacksService(SkillsService skillsService,
                                   ScheduledJobExecutor jobExecutor,
                                   ShellAccessService shellAccessService,
                                   @Lazy ConversationService conversationService,
                                   BotRepository botRepository,
                                   McpService mcpService,
                                   @Autowired(required = false) SmitheryMcpTool smitheryMcpTool,
                                   @Autowired(required = false) SmitherySkillTool smitherySkillTool) {
        this.skillsService = skillsService;
        this.jobExecutor = jobExecutor;
        this.shellAccessService = shellAccessService;
        this.conversationService = conversationService;
        this.botRepository = botRepository;
        this.mcpService = mcpService;
        this.smitheryMcpTool = smitheryMcpTool;
        this.smitherySkillTool = smitherySkillTool;
    }

    public List<ToolCallback> buildToolCallbacks(StreamRequestMetadata meta, String providerId) {
        var filtered = meta.messages().stream().filter(m -> !"tool".equals(m.role())).toList();
        var list = new ArrayList<ToolCallback>();

        list.addAll(Arrays.asList(ToolCallbacks.from(new ChatTools(skillsService, jobExecutor, providerId, filtered,
                meta.conversationLimit(), meta.botName(), botRepository))));
        list.addAll(Arrays.asList(ToolCallbacks.from(new ShellTool(shellAccessService))));

        var botName = meta.botName();
        if (botName != null && !botName.isBlank() && !"default".equalsIgnoreCase(botName)) {
            list.addAll(Arrays.asList(ToolCallbacks.from(new BotToBotTool(conversationService, providerId, botName))));
        }
        if (smitheryMcpTool != null) {
            list.addAll(Arrays.asList(ToolCallbacks.from(smitheryMcpTool)));
        }
        if (smitherySkillTool != null) {
            list.addAll(Arrays.asList(ToolCallbacks.from(smitherySkillTool)));
        }
        mcpService.getToolCallbacksByServer().forEach((serverName, callbacks) -> list.addAll(callbacks));

        return list;
    }
}
