package com.programmersdiary.aidaemon.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class ConversationRepository {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path conversationsDir;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public ConversationRepository(
            @Value("${aidaemon.config-dir:${user.home}/.aidaemon}") String configDir) {
        this.conversationsDir = Path.of(configDir, "conversations");
    }

    @PostConstruct
    void load() throws IOException {
        Files.createDirectories(conversationsDir);
        try (var stream = Files.list(conversationsDir)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> {
                        var conversationFile = dir.resolve("conversation.json");
                        if (Files.exists(conversationFile)) {
                            try {
                                var conv = objectMapper.readValue(conversationFile.toFile(), Conversation.class);
                                conversations.put(conv.id(), conv);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                    });
        }
    }

    public List<Conversation> findAll() {
        return List.copyOf(conversations.values());
    }

    public List<Conversation> findByParentId(String parentId) {
        return conversations.values().stream()
                .filter(c -> parentId.equals(c.parentConversationId()))
                .toList();
    }

    public List<Conversation> findByParticipant(String participant) {
        if (participant == null) return List.of();
        return conversations.values().stream()
                .filter(c -> c.isDirect() && (participant.equals(c.participant1()) || participant.equals(c.participant2())))
                .toList();
    }

    public List<Conversation> findAllForParticipant(String participant) {
        if (participant == null) return findAll();
        return conversations.values().stream()
                .filter(c -> !c.isDirect() || participant.equals(c.participant1()) || participant.equals(c.participant2()))
                .toList();
    }

    public Optional<Conversation> findById(String id) {
        return Optional.ofNullable(conversations.get(id));
    }

    public Conversation save(Conversation conversation) {
        conversations.put(conversation.id(), conversation);
        persist(conversation);
        return conversation;
    }

    public boolean deleteById(String id) {
        var conversation = conversations.remove(id);
        if (conversation == null) return false;
        var convDir = conversationsDir.resolve(id);
        try {
            if (Files.exists(convDir)) {
                try (var walk = Files.walk(convDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    private void persist(Conversation conversation) {
        try {
            var convDir = conversationsDir.resolve(conversation.id());
            Files.createDirectories(convDir);
            objectMapper.writeValue(convDir.resolve("conversation.json").toFile(), conversation);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
