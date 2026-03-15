package com.programmersdiary.aidaemon.bot;

import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.files.FileStorageService;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BotService {

    private final BotRepository repository;
    private final ContextConfig contextConfig;
    private final ChatService chatService;
    private final SkillsService skillsService;
    private final FileStorageService fileStorageService;

    public BotService(BotRepository repository, ContextConfig contextConfig, ChatService chatService,
                      SkillsService skillsService, FileStorageService fileStorageService) {
        this.repository = repository;
        this.contextConfig = contextConfig;
        this.chatService = chatService;
        this.skillsService = skillsService;
        this.fileStorageService = fileStorageService;
    }

    public Bot getBot(String name) {
        return new Bot(name, this, contextConfig, chatService, skillsService, fileStorageService);
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
        return repository.loadSoul(name);
    }

}
