package com.programmersdiary.aidaemon.chat;

import com.programmersdiary.aidaemon.bot.BotService;
import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import com.programmersdiary.aidaemon.skills.SkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatContextBuilderFilesTest {

    @TempDir
    Path tempDir;

    private FileStorageService fileStorageService;
    private ChatContextBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        fileStorageService = new FileStorageService(tempDir.toString());

        var botService = mock(BotService.class);
        var skillsService = mock(SkillsService.class);
        when(skillsService.readMemory()).thenReturn(Map.of("key", "value"));
        when(botService.loadSoul(any())).thenReturn(null);

        builder = new ChatContextBuilder(botService, skillsService, fileStorageService);
    }

    @Test
    void buildMessages_withNoFiles_producesPlainUserMessage() {
        var messages = List.of(ChatMessage.of("user", "hello"));
        var result = builder.buildMessages(messages, null, 0, 0, "sys", null);

        var lastMsg = result.get(result.size() - 1);
        assertInstanceOf(UserMessage.class, lastMsg);
        assertEquals("hello", ((UserMessage) lastMsg).getText());
    }

    @Test
    void buildMessages_withTextFile_includesFileContentInUserMessage() throws IOException {
        var content = "public class Foo {}";
        var attachment = fileStorageService.store("Foo.java", "text/x-java-source", content.getBytes());
        var messages = List.of(ChatMessage.ofWithFiles("user", "review this", List.of(attachment)));

        var result = builder.buildMessages(messages, null, 0, 0, "sys", null);

        var lastMsg = (UserMessage) result.get(result.size() - 1);
        assertTrue(lastMsg.getText().contains("Foo.java"));
        assertTrue(lastMsg.getText().contains("public class Foo {}"));
    }

    @Test
    void buildMessages_withImageFile_includesMediaInUserMessage() throws IOException {
        var pngBytes = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47}; // PNG header
        var attachment = fileStorageService.store("photo.png", "image/png", pngBytes);
        var messages = List.of(ChatMessage.ofWithFiles("user", "describe", List.of(attachment)));

        var result = builder.buildMessages(messages, null, 0, 0, "sys", null);

        var lastMsg = (UserMessage) result.get(result.size() - 1);
        assertFalse(lastMsg.getMedia().isEmpty());
    }

    @Test
    void buildMessages_withFileInHistory_appendsFileReferencesAsText() throws IOException {
        var attachment = fileStorageService.store("data.csv", "text/csv", "a,b\n1,2".getBytes());
        var historyMsg = ChatMessage.ofWithFiles("user", "here is data", List.of(attachment));
        var currentMsg = ChatMessage.of("user", "what did I send?");
        var messages = List.of(historyMsg, ChatMessage.of("assistant", "got it"), currentMsg);

        var result = builder.buildMessages(messages, null, 10000, 0, "sys", null);

        // History user message should contain file name reference, not full content
        var historyUserMsg = result.stream()
                .filter(m -> m instanceof UserMessage)
                .filter(m -> ((UserMessage) m).getText().contains("here is data"))
                .findFirst();
        assertTrue(historyUserMsg.isPresent());
        assertTrue(((UserMessage) historyUserMsg.get()).getText().contains("data.csv"));
    }
}