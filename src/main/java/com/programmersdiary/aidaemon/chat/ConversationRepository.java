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
            stream.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            var conv = objectMapper.readValue(p.toFile(), Conversation.class);
                            conversations.put(conv.id(), conv);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    public List<Conversation> findAll() {
        return List.copyOf(conversations.values());
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
        boolean removed = conversations.remove(id) != null;
        if (removed) {
            try {
                Files.deleteIfExists(conversationsDir.resolve(id + ".json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return removed;
    }

    private void persist(Conversation conversation) {
        try {
            Files.createDirectories(conversationsDir);
            objectMapper.writeValue(conversationsDir.resolve(conversation.name() + ".json").toFile(), conversation);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
