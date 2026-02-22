package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
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
    private final String systemInstructions;
    private final boolean delegationEnabled;
    private final int charsContextWindow;
    private final ObjectMapper objectMapper;

    public ChatService(ProviderConfigRepository configRepository,
                       ChatModelFactory chatModelFactory,
                       SkillsService skillsService,
                       ScheduledJobExecutor jobExecutor,
                       ShellAccessService shellAccessService,
                       McpService mcpService,
                       ConversationRepository conversationRepository,
                       @Value("${aidaemon.system-instructions:}") String systemInstructions,
                       @Value("${aidaemon.delegation-enabled:false}") boolean delegationEnabled,
                       @Value("${aidaemon.delegation-threshold-seconds:30}") int delegationThresholdSeconds,
                       @Value("${aidaemon.chars-context-window:0}") int charsContextWindow) {
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
        this.charsContextWindow = charsContextWindow;
        this.objectMapper = new ObjectMapper();
    }

    public ChatResult chat(String providerId, List<ChatMessage> messages) {
        return chat(providerId, messages, null);
    }

    public ChatResult streamAndCollect(String providerId, List<ChatMessage> messages) {
        var resultRef = new AtomicReference<ChatResult>();
        stream(providerId, messages, null, resultRef::set).blockLast();
        return resultRef.get();
    }

    public ChatResult chat(String providerId, List<ChatMessage> messages, String conversationId) {
        var config = configRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        var filtered = messages.stream().filter(m -> !"tool".equals(m.role())).toList();
        var toolLog = new ArrayList<ChatMessage>();
        var loggingTools = new ArrayList<ToolCallback>();
        Arrays.asList(ToolCallbacks.from(new ChatTools(skillsService, jobExecutor, providerId, filtered, charsContextWindow)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        Arrays.asList(ToolCallbacks.from(new ShellTool(shellAccessService)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        mcpService.getToolCallbacksByServer().forEach((serverName, callbacks) ->
                callbacks.forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, serverName))));

        DelegationTools delegationTools = null;
        if (delegationEnabled && conversationId != null) {
            var conversation = conversationRepository.findById(conversationId).orElse(null);
            delegationTools = new DelegationTools(conversationRepository, conversationId, providerId);
            Arrays.asList(ToolCallbacks.from(delegationTools))
                    .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog)));
        }

        var chatModel = chatModelFactory.create(config, loggingTools);
        var promptOptions = chatModelFactory.promptOptions(config, loggingTools);

        try {
            var response = chatModel.call(buildPrompt(messages, promptOptions));
            var pendingIds = delegationTools != null
                    ? delegationTools.getPendingSubConversationIds() : List.<String>of();
            var result = response.getResult();
            var output = result != null ? result.getOutput() : null;
            var text = output != null ? output.getText() : null;
            var responseText = text != null ? text : "";
            String reasoning = null;
            var parts = new ArrayList<StreamChunk>();
            if (output != null) {
                reasoning = extractReasoning(output);
                if (reasoning != null && !reasoning.isEmpty()) {
                    parts.add(new StreamChunk(StreamChunk.TYPE_REASONING, reasoning));
                }
                if (!responseText.isEmpty()) {
                    parts.add(new StreamChunk(StreamChunk.TYPE_ANSWER, responseText));
                }
            }
            var orderedParts = parts.isEmpty() ? null : parts;
            return new ChatResult(responseText, toolLog, pendingIds, orderedParts, reasoning);
        } catch (Exception e) {
            return new ChatResult("[Error] " + e.getMessage(), toolLog, List.of());
        }
    }

    private static String extractReasoning(AssistantMessage output) {
        var metadata = output.getMetadata();
        if (metadata == null) return null;
        for (var key : REASONING_KEYS) {
            var value = metadata.get(key);
            if (value != null && !value.toString().isEmpty()) {
                return value.toString();
            }
        }
        return null;
    }

    public Flux<StreamChunk> stream(String providerId, List<ChatMessage> messages, String conversationId,
                                   Consumer<ChatResult> onComplete) {
        var config = configRepository.findById(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        var filtered = messages.stream().filter(m -> !"tool".equals(m.role())).toList();
        final var toolLog = new ArrayList<ChatMessage>();
        var sink = Sinks.many().unicast().<StreamChunk>onBackpressureBuffer();
        var onToolChunk = (Consumer<StreamChunk>) c -> sink.tryEmitNext(c);
        var loggingTools = new ArrayList<ToolCallback>();
        Arrays.asList(ToolCallbacks.from(new ChatTools(skillsService, jobExecutor, providerId, filtered, charsContextWindow)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, null, onToolChunk)));
        Arrays.asList(ToolCallbacks.from(new ShellTool(shellAccessService)))
                .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, null, onToolChunk)));
        mcpService.getToolCallbacksByServer().forEach((serverName, callbacks) ->
                callbacks.forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, serverName, onToolChunk))));

        final DelegationTools delegationTools = (delegationEnabled && conversationId != null)
                ? new DelegationTools(conversationRepository, conversationId, providerId)
                : null;
        if (delegationTools != null) {
            Arrays.asList(ToolCallbacks.from(delegationTools))
                    .forEach(t -> loggingTools.add(new LoggingToolCallback(t, toolLog, null, onToolChunk)));
        }

        var chatModel = chatModelFactory.create(config, loggingTools);
        var promptOptions = chatModelFactory.promptOptions(config, loggingTools);
        if (!(chatModel instanceof StreamingChatModel streamingModel)) {
            var result = chat(providerId, messages, conversationId);
            onComplete.accept(result);
            return Flux.just(new StreamChunk(StreamChunk.TYPE_ANSWER, result.response()));
        }

        final StringBuilder contentAccum = new StringBuilder();
        final StringBuilder reasoningAccum = new StringBuilder();
        final var orderedChunks = new ArrayList<StreamChunk>();

        var contentStream = streamingModel.stream(buildPrompt(messages, promptOptions))
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

    private Prompt buildPrompt(List<ChatMessage> messages, ChatOptions promptOptions) {
        var springMessages = new ArrayList<Message>();
        springMessages
        .add(SystemMessage.builder().text(systemInstructions)
        .metadata(cacheControl())
        .build());
        springMessages.addAll(memoryMessages());
        var filtered = messages
            .stream()
            .filter(m -> !"tool".equals(m.role()))
            .toList();
        var history = filtered.subList(0, filtered.size() - 1);
        if (!history.isEmpty()) {
            var trimmed = trimToContextWindow(history, charsContextWindow);
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

    private List<Message> conversationHistory(List<ChatMessage> trimmedHistory) {
        var result = new ArrayList<Message>();
        if (!trimmedHistory.isEmpty()) {
            trimmedHistory.forEach(m -> result.add(toNotCachedSpringMessage(m)));
            result.add(toCachedSpringMessage(trimmedHistory.getLast()));
        }
        return result;
    }

    private static List<ChatMessage> trimToContextWindow(List<ChatMessage> history, int limit) {
        int total = 0;
        int start = history.size();
        for (int i = history.size() - 1; i >= 0; i--) {
            int len = (history.get(i).content() != null)
                ? history.get(i).content().length()
                : 0;
            if (total + len > limit) {
                break;
            }
            total += len;
            start = i;
        }
        return history.subList(start, history.size());
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
