package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class ChatContextBuilder {

    private static final List<String> REASONING_KEYS =
            List.of("thinking", "reasoningContent", "reasoning_content", "reasoning");

    private static final Set<String> TEXT_SUBTYPES = Set.of(
            "plain", "html", "xml", "csv", "markdown", "x-java-source", "x-java",
            "javascript", "typescript", "x-python", "x-sh", "x-shellscript"
    );

    private static final Set<String> TEXT_APP_SUBTYPES = Set.of(
            "json", "xml", "javascript", "typescript", "x-yaml", "yaml",
            "toml", "x-toml", "graphql", "sql"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "py", "js", "ts", "jsx", "tsx", "go", "rs", "kt", "cs",
            "cpp", "c", "h", "rb", "php", "swift", "scala", "sh", "bash",
            "txt", "md", "yaml", "yml", "toml", "ini", "xml", "html", "css",
            "json", "sql", "graphql", "tf", "properties", "env"
    );

    private final BotService botService;
    private final SkillsService skillsService;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    public ChatContextBuilder(BotService botService, SkillsService skillsService) {
        this(botService, skillsService, null);
    }

    public ChatContextBuilder(BotService botService, SkillsService skillsService, FileStorageService fileStorageService) {
        this.botService = botService;
        this.skillsService = skillsService;
        this.fileStorageService = fileStorageService;
        this.objectMapper = new ObjectMapper();
    }

    public List<Message> buildMessages(List<ChatMessage> messages, String replyingBotName,
                                      int conversationLimit, String systemInstructions,
                                      String senderIdentity) {
        var springMessages = new ArrayList<Message>();
        springMessages.add(SystemMessage.builder().text(systemInstructions != null ? systemInstructions : "").metadata(cacheControl()).build());
        var soul = botService.loadSoul(replyingBotName);
        if (soul != null && !soul.isBlank()) {
            springMessages.add(new SystemMessage(soul));
        }
        if (senderIdentity != null && !senderIdentity.isBlank() && !"user".equalsIgnoreCase(senderIdentity)) {
            springMessages.add(new SystemMessage(
                    "You have been triggered by bot \"" + senderIdentity + "\". "
                    + "To continue the conversation, use the writeToConversation tool with the conversation ID and the list of bots to wake up. "
                    + "Use listMyConversations to find your conversation IDs. "
                    + "Only wake up other bots if the conversation should continue — do not loop indefinitely."));
        }
        springMessages.addAll(memoryMessages());
        var filtered = messages.stream().filter(m -> !"tool".equals(m.participant())).toList();
        var history = filtered.subList(0, filtered.size() - 1);
        if (!history.isEmpty()) {
            var trimmed = ContextWindowTrimmer.trimChatHistory(history, conversationLimit);
            int firstInContext = history.size() - trimmed.size();
            springMessages.add(new SystemMessage(
                    "This conversation has " + filtered.size() + " messages. "
                            + "Your current context includes messages from index " + firstInContext + " (inclusive) to the latest. "
                            + "Use retrieve_older_messages tool to fetch earlier messages if needed."));
            springMessages.addAll(conversationHistory(trimmed, replyingBotName));
        }
        springMessages.add(toCurrentMessage(filtered.get(filtered.size() - 1), replyingBotName));
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

    private List<Message> conversationHistory(List<ChatMessage> trimmedHistory, String replyingBotName) {
        var result = new ArrayList<Message>();
        if (!trimmedHistory.isEmpty()) {
            trimmedHistory.forEach(m -> result.add(toHistorySpringMessage(m, replyingBotName)));
            result.add(toCachedSpringMessage(trimmedHistory.get(trimmedHistory.size() - 1), replyingBotName));
        }
        return result;
    }

    private Message toHistorySpringMessage(ChatMessage message, String replyingBotName) {
        return toSpringMessage(message, replyingBotName);
    }

    private Message toCachedSpringMessage(ChatMessage message, String replyingBotName) {
        var p = message.participant();
        var content = message.content() != null ? message.content() : "";
        if ("system".equals(p)) {
            return SystemMessage.builder().text(content).metadata(cacheControl()).build();
        }
        if (replyingBotName != null && replyingBotName.equals(p)) {
            return AssistantMessage.builder()
                    .content(flattenStructuredContent(content))
                    .properties(cacheControl())
                    .build();
        }
        return UserMessage.builder().text(contentWithFileReferences(message)).metadata(cacheControl()).build();
    }

    private Message toSpringMessage(ChatMessage message, String replyingBotName) {
        var p = message.participant();
        if ("system".equals(p)) return new SystemMessage(message.content());
        if (replyingBotName != null && replyingBotName.equals(p)) {
            return new AssistantMessage(flattenStructuredContent(message.content()));
        }
        return new UserMessage(contentWithFileReferences(message));
    }

    private Message toCurrentMessage(ChatMessage message, String replyingBotName) {
        var p = message.participant();
        if ("system".equals(p)) return new SystemMessage(message.content());
        if (replyingBotName != null && replyingBotName.equals(p)) {
            return new AssistantMessage(flattenStructuredContent(message.content()));
        }
        return buildCurrentUserMessage(message);
    }

    private UserMessage buildCurrentUserMessage(ChatMessage message) {
        if (isUserParts(message.content())) {
            return buildCurrentUserMessageFromParts(message.content());
        }
        var textBuilder = new StringBuilder(message.content() != null ? message.content() : "");
        var mediaList = new ArrayList<Media>();

        for (var file : message.files()) {
            processFileForCurrentMessage(file, textBuilder, mediaList);
        }

        var builder = UserMessage.builder().text(textBuilder.toString());
        if (!mediaList.isEmpty()) {
            builder.media(mediaList);
        }
        return builder.build();
    }

    private UserMessage buildCurrentUserMessageFromParts(String content) {
        var textBuilder = new StringBuilder();
        var mediaList = new ArrayList<Media>();
        try {
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) objectMapper.readValue(content, Map.class);
            @SuppressWarnings("unchecked")
            var parts = (List<Map<String, Object>>) data.get("user_parts");
            if (parts != null) {
                for (var part : parts) {
                    var type = String.valueOf(part.get("type"));
                    if ("text".equals(type)) {
                        var partContent = part.get("content");
                        if (partContent != null) {
                            if (textBuilder.length() > 0) textBuilder.append(" ");
                            textBuilder.append(partContent);
                        }
                    } else if ("file".equals(type)) {
                        var id = String.valueOf(part.get("id"));
                        var name = String.valueOf(part.get("name"));
                        var mimeType = String.valueOf(part.get("mimeType"));
                        processFileForCurrentMessage(new FileAttachment(id, name, mimeType), textBuilder, mediaList);
                    }
                }
            }
        } catch (Exception e) {
            textBuilder.append(content);
        }
        var builder = UserMessage.builder().text(textBuilder.toString());
        if (!mediaList.isEmpty()) builder.media(mediaList);
        return builder.build();
    }

    private boolean isUserParts(String content) {
        return content != null && content.trim().startsWith("{\"user_parts\":");
    }

    private String flattenUserParts(String content) {
        try {
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) objectMapper.readValue(content, Map.class);
            @SuppressWarnings("unchecked")
            var parts = (List<Map<String, Object>>) data.get("user_parts");
            if (parts == null) return content;
            var sb = new StringBuilder();
            for (var part : parts) {
                var type = String.valueOf(part.get("type"));
                if ("text".equals(type)) {
                    var partContent = part.get("content");
                    if (partContent != null) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(partContent);
                    }
                } else if ("file".equals(type)) {
                    sb.append("\n[File: ").append(part.get("name")).append("]");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return content;
        }
    }

    private void processFileForCurrentMessage(FileAttachment file, StringBuilder textBuilder, List<Media> mediaList) {
        if (fileStorageService == null) {
            appendFileReference(textBuilder, file);
            return;
        }
        try {
            var bytes = fileStorageService.getBytes(file.id());
            var mimeType = parseSafeMimeType(file.mimeType());

            if (isNativeMedia(mimeType)) {
                mediaList.add(new Media(mimeType, new ByteArrayResource(bytes)));
            } else if (isTextContent(mimeType, file.name())) {
                var content = new String(bytes, StandardCharsets.UTF_8);
                var ext = fileExtension(file.name());
                textBuilder.append("\n\nFile: ").append(file.name())
                        .append("\n```").append(ext).append("\n")
                        .append(content).append("\n```");
            } else {
                var b64 = Base64.getEncoder().encodeToString(bytes);
                textBuilder.append("\n\nFile: ").append(file.name())
                        .append(" (").append(file.mimeType()).append(", base64-encoded):\n")
                        .append(b64);
            }
        } catch (IOException e) {
            appendFileReference(textBuilder, file);
        }
    }

    private String contentWithFileReferences(ChatMessage message) {
        var content = message.content() != null ? message.content() : "";
        if (isUserParts(content)) {
            return flattenUserParts(content);
        }
        if (message.files().isEmpty()) return content;
        var fileNames = message.files().stream()
                .map(FileAttachment::name)
                .collect(Collectors.joining(", "));
        return content + "\n[Attached files: " + fileNames + "]";
    }

    private void appendFileReference(StringBuilder sb, FileAttachment file) {
        sb.append("\n[File: ").append(file.name()).append("]");
    }

    private boolean isNativeMedia(MimeType mimeType) {
        return "image".equals(mimeType.getType())
                || "audio".equals(mimeType.getType())
                || ("application".equals(mimeType.getType()) && "pdf".equals(mimeType.getSubtype()));
    }

    private boolean isTextContent(MimeType mimeType, String filename) {
        if ("text".equals(mimeType.getType())) return true;
        if ("application".equals(mimeType.getType()) && TEXT_APP_SUBTYPES.contains(mimeType.getSubtype())) return true;
        var ext = fileExtension(filename);
        return TEXT_EXTENSIONS.contains(ext);
    }

    private static MimeType parseSafeMimeType(String mimeTypeStr) {
        try {
            return MimeTypeUtils.parseMimeType(mimeTypeStr);
        } catch (Exception e) {
            return MimeTypeUtils.APPLICATION_OCTET_STREAM;
        }
    }

    private static String fileExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
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