package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotDefinition;
import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationParticipantsTest {

    @TempDir
    Path tempDir;

    private ConversationRepository repository;
    private ConversationService service;

    @BeforeEach
    void setUp() throws IOException {
        repository = new ConversationRepository(tempDir.toString());
        repository.load();

        var botService = mock(BotService.class);
        when(botService.listBots()).thenReturn(List.of(
                new BotDefinition("botA"),
                new BotDefinition("botB"),
                new BotDefinition("botC")
        ));

        service = new ConversationService(repository, botService, mock(FileStorageService.class));
    }

    @Test
    void createConversation_withParticipants_savesParticipantsList() {
        var conv = service.createConversation("Team Chat", "provider1", List.of("user", "botA", "botB"));

        assertNotNull(conv.id());
        assertEquals("Team Chat", conv.name());
        assertEquals(List.of("user", "botA", "botB"), conv.participants());
    }

    @Test
    void createConversation_persistsToRepository() {
        var conv = service.createConversation("Project Alpha", "provider1", List.of("user", "botA"));

        var loaded = repository.findById(conv.id()).orElseThrow();
        assertEquals("Project Alpha", loaded.name());
        assertEquals(List.of("user", "botA"), loaded.participants());
    }

    @Test
    void addParticipant_addsToExistingConversation() {
        var conv = service.createConversation("Team Chat", "provider1", List.of("user", "botA"));

        service.addParticipant(conv.id(), "botB");

        var updated = repository.findById(conv.id()).orElseThrow();
        assertTrue(updated.participants().contains("botB"));
        assertTrue(updated.participants().contains("user"));
        assertTrue(updated.participants().contains("botA"));
    }

    @Test
    void addParticipant_doesNotDuplicate() {
        var conv = service.createConversation("Team Chat", "provider1", List.of("user", "botA"));

        service.addParticipant(conv.id(), "botA");

        var updated = repository.findById(conv.id()).orElseThrow();
        assertEquals(1, updated.participants().stream().filter("botA"::equals).count());
    }

    @Test
    void addParticipant_rejectsUnknownBot() {
        var conv = service.createConversation("Team Chat", "provider1", List.of("user", "botA"));

        assertThrows(IllegalArgumentException.class, () -> service.addParticipant(conv.id(), "unknownBot"));
    }

    @Test
    void findAllForParticipant_returnsConversationsWhereUserIsParticipant() {
        var userConv = service.createConversation("User Conv", "p1", List.of("user", "botA"));
        service.createConversation("Bots Only", "p1", List.of("botA", "botB"));

        var results = repository.findAllForParticipant("user");

        assertTrue(results.stream().anyMatch(c -> c.id().equals(userConv.id())));
        assertTrue(results.stream().noneMatch(c -> c.name().equals("Bots Only")));
    }

    @Test
    void findAllForParticipant_returnsBotConversationsForThatBot() {
        var botAConv = service.createConversation("Bot A Conv", "p1", List.of("user", "botA"));
        var botBConv = service.createConversation("Bot B Conv", "p1", List.of("user", "botB"));

        var botAResults = repository.findAllForParticipant("botA");

        assertTrue(botAResults.stream().anyMatch(c -> c.id().equals(botAConv.id())));
        assertTrue(botAResults.stream().noneMatch(c -> c.id().equals(botBConv.id())));
    }

    @Test
    void createConversation_withBlankName_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createConversation("  ", "provider1", List.of("user", "botA")));
    }

    @Test
    void createConversation_withNoParticipants_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createConversation("Chat", "provider1", List.of()));
    }

    @Test
    void createConversation_withUnknownBot_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createConversation("Chat", "provider1", List.of("user", "unknownBot")));
    }

    @Test
    void oldDirectConversation_inJsonFormat_migratesParticipantsOnLoad() throws IOException {
        var oldJson = """
                {
                  "id": "user_botA",
                  "name": "user & botA",
                  "providerId": "p1",
                  "messages": [],
                  "createdAtMillis": 1000,
                  "participant1": "user",
                  "participant2": "botA",
                  "direct": true
                }
                """;
        var convDir = tempDir.resolve("conversations").resolve("user_botA");
        Files.createDirectories(convDir);
        Files.writeString(convDir.resolve("conversation.json"), oldJson);

        var freshRepo = new ConversationRepository(tempDir.toString());
        freshRepo.load();

        var conv = freshRepo.findById("user_botA").orElseThrow();
        assertNotNull(conv.participants());
        assertTrue(conv.participants().contains("user"));
        assertTrue(conv.participants().contains("botA"));

        var userResults = freshRepo.findAllForParticipant("user");
        assertTrue(userResults.stream().anyMatch(c -> c.id().equals("user_botA")));
    }
}
