package com.programmersdiary.aidaemon.skills;

import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImageGenerationToolTest {

    @Test
    void generateImage_storesImageAndReturnsMarkdownReference() throws Exception {
        var imageModel = mock(ImageModel.class);
        var fileStorageService = mock(FileStorageService.class);

        var imageBytes = new byte[]{1, 2, 3, 4};
        var b64 = Base64.getEncoder().encodeToString(imageBytes);

        var image = mock(Image.class);
        when(image.getB64Json()).thenReturn(b64);
        var generation = mock(ImageGeneration.class);
        when(generation.getOutput()).thenReturn(image);
        var imageResponse = mock(ImageResponse.class);
        when(imageResponse.getResult()).thenReturn(generation);
        when(imageModel.call(any(ImagePrompt.class))).thenReturn(imageResponse);

        var attachment = new FileAttachment("file-123", "generated-image.png", "image/png");
        when(fileStorageService.store(eq("conv-1"), eq("generated-image.png"), eq("image/png"), eq(imageBytes)))
                .thenReturn(attachment);

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "conv-1");
        var result = tool.generateImage("a beautiful sunset over the ocean");

        assertTrue(result.contains("/api/files/file-123"), "Result should contain the file URL");
        verify(fileStorageService).store("conv-1", "generated-image.png", "image/png", imageBytes);
    }

    @Test
    void generateImage_returnsErrorMessage_whenImageModelThrows() {
        var imageModel = mock(ImageModel.class);
        var fileStorageService = mock(FileStorageService.class);
        when(imageModel.call(any(ImagePrompt.class))).thenThrow(new RuntimeException("API quota exceeded"));

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "conv-1");
        var result = tool.generateImage("a cat");

        assertTrue(result.toLowerCase().contains("failed") || result.toLowerCase().contains("error"),
                "Result should indicate failure");
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void generateImage_usesConversationIdForFileStorage() throws Exception {
        var imageModel = mock(ImageModel.class);
        var fileStorageService = mock(FileStorageService.class);

        var b64 = Base64.getEncoder().encodeToString(new byte[]{9, 8, 7});
        var image = mock(Image.class);
        when(image.getB64Json()).thenReturn(b64);
        var generation = mock(ImageGeneration.class);
        when(generation.getOutput()).thenReturn(image);
        var imageResponse = mock(ImageResponse.class);
        when(imageResponse.getResult()).thenReturn(generation);
        when(imageModel.call(any())).thenReturn(imageResponse);
        when(fileStorageService.store(any(), any(), any(), any()))
                .thenReturn(new FileAttachment("id", "generated-image.png", "image/png"));

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "specific-conv-id");
        tool.generateImage("a dog");

        verify(fileStorageService).store(eq("specific-conv-id"), any(), any(), any());
    }
}
