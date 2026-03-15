package com.programmersdiary.aidaemon.bot;

import com.programmersdiary.aidaemon.chat.ChatMessage;
import com.programmersdiary.aidaemon.chat.ChatService;
import com.programmersdiary.aidaemon.chat.ContextConfig;
import com.programmersdiary.aidaemon.chat.ContextWindowTrimmer;
import com.programmersdiary.aidaemon.files.FileStorageService;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public TrimmedPersonalMemory loadPersonalMemoryTrimmed(String name, int maxChars) {
        if (maxChars <= 0 || !repository.exists(name)) {
            return new TrimmedPersonalMemory(0, List.of(), 0);
        }
        var entries = repository.loadPersonalMemory(name);
        if (entries.isEmpty()) return new TrimmedPersonalMemory(0, List.of(), 0);
        var trimmed = ContextWindowTrimmer.trimPersonalMemory(entries, maxChars);
        int startIndexInclusive = entries.size() - trimmed.size();
        return new TrimmedPersonalMemory(startIndexInclusive, trimmed, entries.size());
    }

    public List<PersonalMemoryEntry> loadPersonalMemory(String name) {
        if (!repository.exists(name)) {
            return List.of();
        }
        return repository.loadPersonalMemory(name);
    }

    public void appendTurnToPersonalMemory(String botName, List<ChatMessage> contextConversation, String assistantContent) {
        if (!repository.exists(botName)) return;
        var entries = new ArrayList<PersonalMemoryEntry>();
        if (contextConversation != null) {
            for (var m : contextConversation) {
                if ("system".equals(m.participant())) continue;
                var role = botName.equals(m.participant()) ? "assistant" : "user";
                entries.add(new PersonalMemoryEntry(role, m.content() != null ? m.content() : ""));
            }
        }
        entries.add(new PersonalMemoryEntry("assistant", assistantContent != null ? assistantContent : ""));
        repository.appendToPersonalMemory(botName, entries);
    }
}
