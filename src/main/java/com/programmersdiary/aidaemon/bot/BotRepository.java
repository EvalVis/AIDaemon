package com.programmersdiary.aidaemon.bot;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Repository
public class BotRepository {

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
}

