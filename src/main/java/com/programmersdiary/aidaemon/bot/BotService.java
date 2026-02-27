package com.programmersdiary.aidaemon.bot;

import org.springframework.stereotype.Service;

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
}

