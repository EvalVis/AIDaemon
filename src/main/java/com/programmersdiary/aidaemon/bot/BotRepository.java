package com.programmersdiary.aidaemon.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Repository
public class BotRepository {

    private static final TypeReference<List<PersonalMemoryEntry>> MEMORY_LIST_TYPE = new TypeReference<>() {};
    private static final List<PersonalMemoryEntry> EMPTY_MEMORY = List.of();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path botsDir;

    public BotRepository(@Value("${aidaemon.config-dir:${user.home}/.aidaemon}") String configDir) {
        this.botsDir = Path.of(configDir, "bots");
    }

    @PostConstruct
    void init() {
        try {
            Files.createDirectories(botsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> findAllNames() {
        try (var stream = Files.list(botsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(String::compareToIgnoreCase)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String name) {
        return Files.isDirectory(botsDir.resolve(name));
    }

    public void create(String name, String soul) {
        var dir = botsDir.resolve(name);
        if (Files.exists(dir)) {
            throw new IllegalArgumentException("Bot already exists: " + name);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SOUL.md"), soul, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String loadSoul(String name) {
        var soulPath = botsDir.resolve(name).resolve("SOUL.md");
        if (!Files.exists(soulPath)) {
            throw new IllegalArgumentException("SOUL.md not found for bot: " + name);
        }
        try {
            return Files.readString(soulPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<PersonalMemoryEntry> loadPersonalMemory(String name) {
        var path = botsDir.resolve(name).resolve("personal_memory.json");
        if (!Files.exists(path)) {
            return EMPTY_MEMORY;
        }
        try {
            var list = objectMapper.readValue(path.toFile(), MEMORY_LIST_TYPE);
            return list != null ? list : EMPTY_MEMORY;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void appendToPersonalMemory(String name, List<PersonalMemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        var dir = botsDir.resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Bot folder not found: " + name);
        }
        var path = dir.resolve("personal_memory.json");
        var existing = path.toFile().exists() ? loadPersonalMemory(name) : new ArrayList<PersonalMemoryEntry>();
        existing = new ArrayList<>(existing);
        existing.addAll(entries);
        try {
            Files.createDirectories(dir);
            objectMapper.writeValue(path.toFile(), existing);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

