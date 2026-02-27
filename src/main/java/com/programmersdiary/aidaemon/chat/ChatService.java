package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.bot.PersonalMemoryEntry;
import com.programmersdiary.aidaemon.delegation.DelegationTools;
import com.programmersdiary.aidaemon.mcp.McpService;
import com.programmersdiary.aidaemon.provider.ChatModelFactory;
import com.programmersdiary.aidaemon.provider.ProviderConfigRepository;
import com.programmersdiary.aidaemon.scheduling.ScheduledJobExecutor;
import com.programmersdiary.aidaemon.skills.ChatTools;
import com.programmersdiary.aidaemon.skills.ShellAccessService;
import com.programmersdiary.aidaemon.skills.ShellTool;
import com.programmersdiary.aidaemon.skills.SkillsService;
import com.programmersdiary.aidaemon.skills.SmitheryMcpTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class ChatService {

    private static final List<String> REASONING_KEYS =
            List.of("thinking", "reasoningContent", "reasoning_content", "reasoning");

    private final ProviderConfigRepository configRepository;
    private final ChatModelFactory chatModelFactory;
    private final SkillsService skillsService;
    private final ScheduledJobExecutor jobExecutor;
    private final ShellAccessService shellAccessService;
    private final McpService mcpService;
    private final ConversationRepository conversationRepository;
    private final BotService botService;
    private final String systemInstructions;
    private final boolean delegationEnabled;
    private final int charsLimit;
    private final double personalMemoryRatio;
    private final ObjectMapper objectMapper;
    private final SmitheryMcpTool smitheryMcpTool;

    public ChatService(ProviderConfigRepository configRepository,
                       ChatModelFactory chatModelFactory,
                       SkillsService skillsService,
                       ScheduledJobExecutor jobExecutor,
                       ShellAccessService shellAccessService,
                       McpService mcpService,
                       ConversationRepository conversationRepository,
                       BotService botService,
                       @Autowired(required = false) SmitheryMcpTool smitheryMcpTool,
                       @Value("${aidaemon.system-instructions:}") String systemInstructions,
                       @Value("${aidaemon.delegation-enabled:false}") boolean delegationEnabled,
                       @Value("${aidaemon.delegation-threshold-seconds:30}") int delegationThresholdSeconds,
                       @Value("${aidaemon.context-window.chars-limit:${aidaemon.chars-context-window:0}}") int charsLimit,
                       @Value("${aidaemon.context-window.personal-memory-ratio:0}") double personalMemoryRatio) {
        this.configRepository = configRepository;
        this.chatModelFactory = chatModelFactory;
        this.skillsService = skillsService;
        this.jobExecutor = jobExecutor;
        this.shellAccessService = shellAccessService;
        this.mcpService = mcpService;
        this.conversationRepository = conversationRepository;
        this.botService = botService;
        this.smitheryMcpTool = smitheryMcpTool;
        this.systemInstructions = systemInstructions
                .replace("{threshold}", String.valueOf(delegationThresholdSeconds));
        this.delegationEnabled = delegationEnabled;
        this.charsLimit = charsLimit;
        this.personalMemoryRatio = Math.max(0, Math.min(1, personalMemoryRatio));
        this.objectMapper = new ObjectMapper();
    }

    public ChatResult streamAndCollect(String providerId, List<ChatMessage> messages) {
        return streamAndCollect(providerId, messages, null, null);
    }

    public ChatResult streamAndCollect(String providerId, List<ChatMessage> messages, String conversationId) {
        return streamAndCollect(providerId, messages, conversationId, null);
    }

    public ChatResult streamAndCollect(String providerId, List<ChatMessage> messages, String conversationId, String botName) {
        var resultRef = new AtomicReference<ChatResult>();
        stream(providerId, messages, conversationId, resultRef::set, botName).blockLast();
        return resultRef.get();
    }

    public Flux<StreamChunk> stream(String providerId, List<ChatMessage> messages, String conversationId,
                                   Consumer<ChatResult> onComplete) {
        return stream(providerId, messages, conversationId, onComplete, null);
    }

    public Flux<StreamChunk> stream(String providerId, List<ChatMessage> messages, String conversationId,
                                   Consumer<ChatResult> onComplete,
                                   String botName) {
        var config = configRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        var namedBot = botName != null && !botName.isBlank() && !"default".equalsIgnoreCase(botName);
        var conversationLimit = charsLimit;
        var personalMemoryLimit = 0;
        if (namedBot) {
            conversationLimit = (int) (charsLimit * (1 - personalMemoryRatio));
            personalMemoryLimit = (int) (charsLimit * personalMemoryRatio);
        }

        var filtered = messages.stream().filter(m -> !"tool".equals(m.role())).toList();
        final var toolLog = new ArrayList<ChatMessage>();
        var sink = Sinks.many().unicast().<StreamChunk>onBackpressureBuffer();
        var onToolChunk = (Consumer<StreamChunk>) c -> sink.tryEmitNext(c);
        var loggingTools = new ArrayList<ToolCallback>();
        Arrays.asList(ToolCallbacks.from(new ChatTools(skillsService, jobExecutor, providerId, filtered, conversationLimit)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, null, onToolChunk)));
        Arrays.asList(ToolCallbacks.from(new ShellTool(shellAccessService)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, null, onToolChunk)));
        if (smitheryMcpTool != null) {
            Arrays.asList(ToolCallbacks.from(smitheryMcpTool))
                    .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, null, onToolChunk)));
        }
        mcpService.getToolCallbacksByServer().forEach((serverName, callbacks) ->
                callbacks.forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, serverName, onToolChunk))));

        final DelegationTools delegationTools = (delegationEnabled && conversationId != null)
                ? new DelegationTools(conversationRepository, conversationId, providerId, botName)
                : null;
        if (delegationTools != null) {
            Arrays.asList(ToolCallbacks.from(delegationTools))
                    .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, null, onToolChunk)));
        }

        var chatModel = chatModelFactory.create(config, loggingTools);
        var promptOptions = chatModelFactory.promptOptions(config, loggingTools);
        if (!(chatModel instanceof StreamingChatModel streamingModel)) {
            var error = "Provider does not support streaming; only streaming models are supported.";
            onComplete.accept(new ChatResult("[Error] " + error, toolLog, List.of()));
            return Flux.just(new StreamChunk(StreamChunk.TYPE_ANSWER, "[Error] " + error));
        }

        final StringBuilder contentAccum = new StringBuilder();
        final StringBuilder reasoningAccum = new StringBuilder();
        final var orderedChunks = new ArrayList<StreamChunk>();

        var contentStream = streamingModel.stream(buildPrompt(messages, promptOptions, botName, conversationLimit, personalMemoryLimit))
                .flatMap(response -> {
                    var c = toStreamChunk(response);
                    return c != null ? Flux.just(c) : Flux.empty();
                })
                .doOnNext(c -> {
                    if (StreamChunk.TYPE_REASONING.equals(c.type())) {
                        reasoningAccum.append(c.content());
                    } else if (StreamChunk.TYPE_ANSWER.equals(c.type())) {
                        contentAccum.append(c.content());
                    }
                })
                .doOnComplete(() -> sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST))
                .doOnError(e -> sink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST));

        var merged = Flux.merge(contentStream, sink.asFlux())
                .doOnNext(c -> {
                    if (StreamChunk.TYPE_ANSWER.equals(c.type()) || StreamChunk.TYPE_TOOL.equals(c.type())) {
                        orderedChunks.add(c);
                    }
                });
        return merged
                .doOnComplete(() -> {
                    var pendingIds = delegationTools != null
                            ? delegationTools.getPendingSubConversationIds() : List.<String>of();
                    var parts = coalesceOrderedChunks(orderedChunks);
                    var reasoning = reasoningAccum.isEmpty() ? null : reasoningAccum.toString();
                    var partsWithReasoning = new ArrayList<StreamChunk>();
                    if (reasoning != null && !reasoning.isEmpty()) {
                        partsWithReasoning.add(new StreamChunk(StreamChunk.TYPE_REASONING, reasoning));
                    }
                    partsWithReasoning.addAll(parts);
                    onComplete.accept(new ChatResult(contentAccum.toString(), toolLog, pendingIds,
                            partsWithReasoning.isEmpty() ? null : partsWithReasoning, reasoning));
                })
                .doOnError(e -> onComplete.accept(new ChatResult("[Error] " + e.getMessage(), toolLog, List.of())));
    }

    private static List<StreamChunk> coalesceOrderedChunks(List<StreamChunk> orderedChunks) {
        if (orderedChunks.isEmpty()) {
            return List.of();
        }
        var parts = new ArrayList<StreamChunk>();
        var answerAccum = new StringBuilder();
        for (var c : orderedChunks) {
            if (StreamChunk.TYPE_ANSWER.equals(c.type())) {
                answerAccum.append(c.content());
            } else {
                if (answerAccum.length() > 0) {
                    parts.add(new StreamChunk(StreamChunk.TYPE_ANSWER, answerAccum.toString()));
                    answerAccum.setLength(0);
                }
                parts.add(c);
            }
        }
        if (answerAccum.length() > 0) {
            parts.add(new StreamChunk(StreamChunk.TYPE_ANSWER, answerAccum.toString()));
        }
        return parts;
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
            for (var key : REASONING_KEYS) {
                var value = metadata.get(key);
                if (value != null && !value.toString().isEmpty()) {
                    return new StreamChunk(StreamChunk.TYPE_REASONING, value.toString());
                }
            }
            if (metadata.containsKey("signature")) {
                var text = output.getText();
                if (text != null && !text.isEmpty()) {
                    return new StreamChunk(StreamChunk.TYPE_REASONING, text);
                }
            }
        }
        var text = output.getText();
        return new StreamChunk(StreamChunk.TYPE_ANSWER, text != null ? text : "");
    }

    private static Map<String, Object> cacheControl() {
        return Map.of("cache_control", Map.of("type", "ephemeral"));
    }

    private Prompt buildPrompt(List<ChatMessage> messages, ChatOptions promptOptions, String botName,
                               int conversationLimit, int personalMemoryLimit) {
        var springMessages = new ArrayList<Message>();
        springMessages
        .add(SystemMessage.builder().text(systemInstructions)
        .metadata(cacheControl())
        .build());
        var soul = botService.loadSoul(botName);
        if (soul != null && !soul.isBlank()) {
            springMessages.add(new SystemMessage(soul));
        }
        if (personalMemoryLimit > 0) {
            var personalEntries = botService.loadPersonalMemoryTrimmed(botName, personalMemoryLimit);
            if (!personalEntries.isEmpty()) {
                springMessages.add(new SystemMessage(
                    "Personal memory (recent interactions across conversations), limited to " + personalMemoryLimit + " characters. Use retrieve_older_messages for conversation history."));
                springMessages.addAll(personalMemoryToMessages(personalEntries));
            }
        }
        springMessages.addAll(memoryMessages());
        var filtered = messages
            .stream()
            .filter(m -> !"tool".equals(m.role()))
            .toList();
        var history = filtered.subList(0, filtered.size() - 1);
        if (!history.isEmpty()) {
            var trimmed = ContextWindowTrimmer.trimToLimit(history, conversationLimit);
            int firstInContext = history.size() - trimmed.size();
            springMessages.add(new SystemMessage(
                "This conversation has " + filtered.size() + " messages. "
                + "Your current context includes messages from index " + firstInContext + " (inclusive) to the latest. "
                + "Use retrieve_older_messages tool to fetch earlier messages if needed."));
            springMessages.addAll(conversationHistory(trimmed));
        }
        springMessages.add(toNotCachedSpringMessage(filtered.getLast()));
        return promptOptions != null
                ? new Prompt(springMessages, promptOptions)
                : new Prompt(springMessages);
    }

    private List<Message> memoryMessages() {
        var memory = skillsService.readMemory().entrySet().stream().toList();
        var result = new ArrayList<Message>();
        memory
            .subList(0, memory.size() - 1)
            .forEach(e -> result.add(new SystemMessage(e.getKey() + ": " + e.getValue())));
        result.add(
            SystemMessage
            .builder()
            .text(memory.getLast().getKey() + ": " + memory.getLast().getValue())
            .metadata(cacheControl())
            .build()
        );
        return result;
    }

    private List<Message> personalMemoryToMessages(List<PersonalMemoryEntry> entries) {
        var result = new ArrayList<Message>();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            var content = e.content() != null ? e.content() : "";
            var isLast = i == entries.size() - 1;
            switch (e.role().toLowerCase()) {
                case "user" -> result.add(isLast
                    ? UserMessage.builder().text(content).metadata(cacheControl()).build()
                    : new UserMessage(content));
                case "assistant" -> result.add(isLast
                    ? AssistantMessage.builder().content(content).properties(cacheControl()).build()
                    : new AssistantMessage(content));
                default -> result.add(new SystemMessage("[Tool] " + content));
            }
        }
        return result;
    }

    private List<Message> conversationHistory(List<ChatMessage> trimmedHistory) {
        var result = new ArrayList<Message>();
        if (!trimmedHistory.isEmpty()) {
            trimmedHistory.forEach(m -> result.add(toNotCachedSpringMessage(m)));
            result.add(toCachedSpringMessage(trimmedHistory.getLast()));
        }
        return result;
    }

    private Message toNotCachedSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(
                flattenStructuredContent(message.content())
            );
            default -> new UserMessage(message.content());
        };
    }

    private Message toCachedSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case "system" -> SystemMessage
                .builder()
                .text(message.content())
                .metadata(cacheControl())
                .build();
            case "assistant" -> AssistantMessage
                .builder()
                .content(flattenStructuredContent(message.content()))
                .properties(cacheControl())
                .build();
            default -> UserMessage
                .builder()
                .text(message.content())
                .metadata(cacheControl())
                .build();
        };
    }

    private String flattenStructuredContent(String content) {
        if (content == null || !content.trim().startsWith("{\"parts\":")) {
            return content != null ? content : "";
        }
        try {
            @SuppressWarnings("unchecked")
            var map = objectMapper.readValue(content, Map.class);
            var parts = (List<?>) map.get("parts");
            if (parts == null) return content;
            var sb = new StringBuilder();
            for (var o : parts) {
                if (!(o instanceof Map<?, ?> p)) continue;
                var type = p.get("type");
                if (type != null && REASONING_KEYS.contains(type.toString())) continue;
                var partContent = p.get("content");
                if (partContent == null) continue;
                var contentStr = partContent.toString();
                if ("answer".equals(type)) {
                    sb.append(contentStr);
                } else {
                    sb.append("\n[Tool]\n").append(contentStr).append("\n");
                }
            }
            return sb.toString();
        } catch (JsonProcessingException | ClassCastException e) {
            return content;
        }
    }

}
