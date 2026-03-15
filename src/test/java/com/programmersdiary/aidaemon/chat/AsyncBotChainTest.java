package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotDefinition;
import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncBotChainTest {

    @TempDir
    Path tempDir;

    private ConversationRepository repository;
    private BotService botService;
    private ConversationService service;

    @BeforeEach
    void setUp() throws IOException {
        repository = new ConversationRepository(tempDir.toString());
        repository.load();
        botService = mock(BotService.class);
        when(botService.listBots()).thenReturn(List.of(
                new BotDefinition("botA"),
                new BotDefinition("botB")
        ));
        service = new ConversationService(repository, botService, mock(FileStorageService.class));
    }

    @Test
    void triggerBotReplyAsync_triggersOnlySpecifiedBot() throws Exception {
        var botABot = mock(com.programmersdiary.aidaemon.bot.Bot.class);
        var botBBot = mock(com.programmersdiary.aidaemon.bot.Bot.class);

        var botBResult = mock(com.programmersdiary.aidaemon.chat.ChatResult.class);
        when(botBResult.assistantContent()).thenReturn("botB reply");

        when(botService.getBot("botA")).thenReturn(botABot);
        when(botService.getBot("botB")).thenReturn(botBBot);
        when(botBBot.chat(any(), any(), any(), any())).thenReturn(botBResult);

        var conv = new Conversation("conv-1", "test", "provider1",
                new ArrayList<>(), System.currentTimeMillis(), List.of("user", "botA", "botB"));
        conv.messages().add(ChatMessage.of("botA", "Hey botB, do something"));
        repository.save(conv);

        service.triggerBotReplyAsync("conv-1", "botB");

        verify(botBBot, timeout(2000)).chat(any(), any(), eq("conv-1"), any());
        verify(botABot, never()).chat(any(), any(), any(), any());
    }

    @Test
    void triggerBotReplyAsync_doesNotAutoChainToSender() throws Exception {
        var botABot = mock(com.programmersdiary.aidaemon.bot.Bot.class);
        var botBBot = mock(com.programmersdiary.aidaemon.bot.Bot.class);

        var result = mock(com.programmersdiary.aidaemon.chat.ChatResult.class);
        when(result.assistantContent()).thenReturn("reply");

        when(botService.getBot("botA")).thenReturn(botABot);
        when(botService.getBot("botB")).thenReturn(botBBot);
        when(botBBot.chat(any(), any(), any(), any())).thenReturn(result);

        var conv = new Conversation("conv-2", "test", "provider1",
                new ArrayList<>(), System.currentTimeMillis(), List.of("botA", "botB"));
        conv.messages().add(ChatMessage.of("botA", "message"));
        repository.save(conv);

        service.triggerBotReplyAsync("conv-2", "botB");

        Thread.sleep(500);

        verify(botBBot, times(1)).chat(any(), any(), any(), any());
        verify(botABot, never()).chat(any(), any(), any(), any());
    }
}
