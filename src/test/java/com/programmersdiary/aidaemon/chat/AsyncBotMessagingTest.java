package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotDefinition;
import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncBotMessagingTest {

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
                new BotDefinition("botB")
        ));

        service = new ConversationService(repository, botService, mock(FileStorageService.class));
    }

    @Test
    void sendMessageToParticipant_forBotTarget_writesMessageToConversationBeforeReturning() {
        service.sendMessageToParticipant("botA", "botB", "hello", "provider1");

        var convId = Conversation.canonicalId("botA", "botB");
        var conv = repository.findById(convId).orElseThrow();
        assertEquals(1, conv.messages().size());
        assertEquals("hello", conv.messages().get(0).content());
        assertEquals("botA", conv.messages().get(0).participant());
    }

    @Test
    void sendMessageToParticipant_forBotTarget_returnsConfirmationContainingConversationId() {
        var result = service.sendMessageToParticipant("botA", "botB", "hello", "provider1");

        var convId = Conversation.canonicalId("botA", "botB");
        assertTrue(result.contains(convId), "Return message should include conversation ID so bot can save it to memory");
    }

    @Test
    void sendMessageToParticipant_forBotTarget_doesNotReturnTargetBotReplyDirectly() {
        var result = service.sendMessageToParticipant("botA", "botB", "hello", "provider1");

        assertFalse(result.toLowerCase().contains("hello"), "Should not echo sender message");
        assertTrue(result.toLowerCase().contains("await") || result.toLowerCase().contains("notif"),
                "Should indicate that a response is pending");
    }

    @Test
    void sendMessageToParticipant_forBotTarget_returnsWithoutWaitingForAsyncReply() {
        var start = System.currentTimeMillis();
        service.sendMessageToParticipant("botA", "botB", "hello", "provider1");
        var elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2000, "Should return quickly since bot reply is async, took: " + elapsed + "ms");
    }

    @Test
    void sendMessageToParticipant_forUserTarget_writesMessageToConversation() {
        service.sendMessageToParticipant("botA", "user", "hi user", "provider1");

        var convId = Conversation.canonicalId("botA", "user");
        var conv = repository.findById(convId).orElseThrow();
        assertEquals(1, conv.messages().size());
        assertEquals("hi user", conv.messages().get(0).content());
    }

    @Test
    void sendMessageToParticipant_forUserTarget_returnsWrittenConfirmation() {
        var result = service.sendMessageToParticipant("botA", "user", "hi user", "provider1");

        assertNotNull(result);
        assertFalse(result.isBlank());
    }
}
