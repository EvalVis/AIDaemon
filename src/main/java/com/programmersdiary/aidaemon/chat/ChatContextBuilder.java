package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.bot.PersonalMemoryEntry;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChatContextBuilder {

    private static final List<String> REASONING_KEYS =
            List.of("thinking", "reasoningContent", "reasoning_content", "reasoning");

    private final BotService botService;
    private final SkillsService skillsService;
    private final ObjectMapper objectMapper;

    public ChatContextBuilder(BotService botService, SkillsService skillsService) {
        this.botService = botService;
        this.skillsService = skillsService;
        this.objectMapper = new ObjectMapper();
    }

    public List<Message> buildMessages(List<ChatMessage> messages, String botName,
                                      int conversationLimit, int personalMemoryLimit, String systemInstructions,
                                      String senderIdentity) {
        var springMessages = new ArrayList<Message>();
        springMessages.add(SystemMessage.builder().text(systemInstructions != null ? systemInstructions : "").metadata(cacheControl()).build());
        var soul = botService.loadSoul(botName);
        if (soul != null && !soul.isBlank()) {
            springMessages.add(new SystemMessage(soul));
        }
        if (senderIdentity != null && !senderIdentity.isBlank() && !"user".equalsIgnoreCase(senderIdentity)) {
            springMessages.add(new SystemMessage(
                    "The message is from bot named \"" + senderIdentity + "\"."));
        }
        if (personalMemoryLimit > 0) {
            var trimmed = botService.loadPersonalMemoryTrimmed(botName, personalMemoryLimit);
            if (!trimmed.entries().isEmpty()) {
                springMessages.add(new SystemMessage(
                        "Personal memory (recent interactions across conversations). "
                                + trimmed.totalEntryCount() + " messages in total. Current context: message index "
                                + trimmed.startIndexInclusive() + " (inclusive) to latest. Use retrieve_older_messages for conversation history; use retrieve_older_personal_memory for older personal memory."));
                springMessages.addAll(personalMemoryToMessages(trimmed.entries()));
            }
        }
        springMessages.addAll(memoryMessages());
        var filtered = messages.stream().filter(m -> !"tool".equals(m.role())).toList();
        var history = filtered.subList(0, filtered.size() - 1);
        if (!history.isEmpty()) {
            var trimmed = ContextWindowTrimmer.trimChatHistory(history, conversationLimit);
            int firstInContext = history.size() - trimmed.size();
            springMessages.add(new SystemMessage(
                    "This conversation has " + filtered.size() + " messages. "
                            + "Your current context includes messages from index " + firstInContext + " (inclusive) to the latest. "
                            + "Use retrieve_older_messages tool to fetch earlier messages if needed."));
            springMessages.addAll(conversationHistory(trimmed));
        }
        springMessages.add(toNotCachedSpringMessage(filtered.get(filtered.size() - 1)));
        return springMessages;
    }

    private List<Message> memoryMessages() {
        var memory = skillsService.readMemory().entrySet().stream().toList();
        var result = new ArrayList<Message>();
        memory.subList(0, memory.size() - 1)
                .forEach(e -> result.add(new SystemMessage(e.getKey() + ": " + e.getValue())));
        result.add(SystemMessage.builder()
                .text(memory.get(memory.size() - 1).getKey() + ": " + memory.get(memory.size() - 1).getValue())
                .metadata(cacheControl())
                .build());
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
            result.add(toCachedSpringMessage(trimmedHistory.get(trimmedHistory.size() - 1)));
        }
        return result;
    }

    private Message toNotCachedSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(flattenStructuredContent(message.content()));
            default -> new UserMessage(message.content());
        };
    }

    private Message toCachedSpringMessage(ChatMessage message) {
        return switch (message.role()) {
            case "system" -> SystemMessage.builder().text(message.content()).metadata(cacheControl()).build();
            case "assistant" -> AssistantMessage.builder()
                    .content(flattenStructuredContent(message.content()))
                    .properties(cacheControl())
                    .build();
            default -> UserMessage.builder().text(message.content()).metadata(cacheControl()).build();
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

    private static Map<String, Object> cacheControl() {
        return Map.of("cache_control", Map.of("type", "ephemeral"));
    }
}
