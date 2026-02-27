package com.programmersdiary.aidaemon.bot;

import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatResult;
import com.programmersdiary.aidaemon.chat.ContextWindowTrimmer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class BotService {

    private final BotRepository repository;

    public BotService(BotRepository repository) {
        this.repository = repository;
    }

    public List<BotDefinition> listBots() {
        return repository.findAllNames().stream()
                .map(BotDefinition::new)
                .toList();
    }

    public BotDefinition create(String name, String soul) {
        var trimmedName = name != null ? name.trim() : "";
        var trimmedSoul = soul != null ? soul.trim() : "";
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Bot name is required");
        }
        if ("default".equalsIgnoreCase(trimmedName)) {
            throw new IllegalArgumentException("Bot name 'default' is reserved");
        }
        if (trimmedSoul.isEmpty()) {
            throw new IllegalArgumentException("Bot soul description is required");
        }
        if (trimmedName.contains("/") || trimmedName.contains("\\") || trimmedName.contains("..")) {
            throw new IllegalArgumentException("Bot name contains invalid characters");
        }
        repository.create(trimmedName, trimmedSoul);
        return new BotDefinition(trimmedName);
    }

    public String loadSoul(String name) {
        if (name == null || name.isBlank() || "default".equalsIgnoreCase(name)) {
            return null;
        }
        return repository.loadSoul(name);
    }

    public List<PersonalMemoryEntry> loadPersonalMemoryTrimmed(String name, int maxChars) {
        if (name == null || name.isBlank() || "default".equalsIgnoreCase(name) || maxChars <= 0) {
            return List.of();
        }
        if (!repository.exists(name)) {
            return List.of();
        }
        var entries = repository.loadPersonalMemory(name);
        if (entries.isEmpty()) return List.of();
        return ContextWindowTrimmer.trimPersonalMemory(entries, maxChars);
    }

    public void appendTurnToPersonalMemory(String botName, String userContent,
                                           List<ChatMessage> toolMessages, String assistantContent) {
        if (botName == null || botName.isBlank() || "default".equalsIgnoreCase(botName)) {
            return;
        }
        if (!repository.exists(botName)) return;
        var entries = new ArrayList<PersonalMemoryEntry>();
        entries.add(new PersonalMemoryEntry("user", userContent != null ? userContent : ""));
        if (toolMessages != null) {
            for (var m : toolMessages) {
                entries.add(new PersonalMemoryEntry("tool", m.content() != null ? m.content() : ""));
            }
        }
        entries.add(new PersonalMemoryEntry("assistant", assistantContent != null ? assistantContent : ""));
        repository.appendToPersonalMemory(botName, entries);
    }
}

