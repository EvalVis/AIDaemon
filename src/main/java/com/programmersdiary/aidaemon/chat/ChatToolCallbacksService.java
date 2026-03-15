package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotRepository;
import com.programmersdiary.aidaemon.files.FileStorageService;
import com.programmersdiary.aidaemon.mcp.McpService;
import com.programmersdiary.aidaemon.provider.ProviderConfigRepository;
import com.programmersdiary.aidaemon.provider.ProviderType;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import com.programmersdiary.aidaemon.skills.ChatTools;
import com.programmersdiary.aidaemon.skills.FileEditTool;
import com.programmersdiary.aidaemon.skills.ImageGenerationTool;
import com.programmersdiary.aidaemon.skills.ShellAccessService;
import com.programmersdiary.aidaemon.skills.ShellTool;
import com.programmersdiary.aidaemon.skills.SkillsService;
import com.programmersdiary.aidaemon.skills.SmitheryMcpTool;
import com.programmersdiary.aidaemon.skills.SmitherySkillTool;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@Service
public class ChatToolCallbacksService {

    private final SkillsService skillsService;
    private final ScheduledJobExecutor jobExecutor;
    private final ShellAccessService shellAccessService;
    private final ToolApprovalService toolApprovalService;
    private final ConversationService conversationService;
    private final BotRepository botRepository;
    private final McpService mcpService;
    private final SmitheryMcpTool smitheryMcpTool;
    private final SmitherySkillTool smitherySkillTool;
    private final ProviderConfigRepository providerConfigRepository;
    private final FileStorageService fileStorageService;

    public ChatToolCallbacksService(SkillsService skillsService,
                                   ScheduledJobExecutor jobExecutor,
                                   ShellAccessService shellAccessService,
                                   ToolApprovalService toolApprovalService,
                                   @Lazy ConversationService conversationService,
                                   BotRepository botRepository,
                                   McpService mcpService,
                                   @Autowired(required = false) SmitheryMcpTool smitheryMcpTool,
                                   @Autowired(required = false) SmitherySkillTool smitherySkillTool,
                                   ProviderConfigRepository providerConfigRepository,
                                   FileStorageService fileStorageService) {
        this.skillsService = skillsService;
        this.jobExecutor = jobExecutor;
        this.shellAccessService = shellAccessService;
        this.toolApprovalService = toolApprovalService;
        this.conversationService = conversationService;
        this.botRepository = botRepository;
        this.mcpService = mcpService;
        this.smitheryMcpTool = smitheryMcpTool;
        this.smitherySkillTool = smitherySkillTool;
        this.providerConfigRepository = providerConfigRepository;
        this.fileStorageService = fileStorageService;
    }

    public List<ToolCallback> buildFileEditToolCallbacks(StreamRequestMetadata meta, Consumer<StreamChunk> onChunk) {
        return Arrays.asList(ToolCallbacks.from(
                new FileEditTool(shellAccessService, toolApprovalService::requestApproval, onChunk)));
    }

    public List<ToolCallback> buildToolCallbacks(StreamRequestMetadata meta, String providerId,
                                                  Consumer<StreamChunk> onFileChangeChunk) {
        var filtered = meta.messages().stream().filter(m -> !"tool".equals(m.role())).toList();
        var list = new ArrayList<ToolCallback>();

        list.addAll(Arrays.asList(ToolCallbacks.from(new ChatTools(skillsService, jobExecutor, providerId, filtered,
                meta.conversationLimit(), meta.botName(), botRepository))));
        list.addAll(Arrays.asList(ToolCallbacks.from(new ShellTool(shellAccessService))));

        var botName = meta.botName();
        if (botName != null && !botName.isBlank()) {
            list.addAll(Arrays.asList(ToolCallbacks.from(new BotToBotTool(conversationService, botRepository, providerId, botName))));
        }
        if (smitheryMcpTool != null) {
            list.addAll(Arrays.asList(ToolCallbacks.from(smitheryMcpTool)));
        }
        if (smitherySkillTool != null) {
            list.addAll(Arrays.asList(ToolCallbacks.from(smitherySkillTool)));
        }
        mcpService.getToolCallbacksByServer().forEach((serverName, callbacks) -> list.addAll(callbacks));

        providerConfigRepository.findAll().stream()
                .filter(c -> c.type() == ProviderType.DALLE_3)
                .findFirst()
                .ifPresent(dalleConfig -> {
                    var factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(Duration.ofSeconds(30));
                    factory.setReadTimeout(Duration.ofSeconds(120));
                    var imageApi = OpenAiImageApi.builder()
                            .apiKey(dalleConfig.apiKey())
                            .restClientBuilder(RestClient.builder().requestFactory(factory))
                            .build();
                    var imageModel = new OpenAiImageModel(imageApi);
                    list.addAll(Arrays.asList(ToolCallbacks.from(
                            new ImageGenerationTool(fileStorageService, imageModel, meta.conversationId()))));
                });

        return list;
    }
}
