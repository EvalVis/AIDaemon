package com.programmersdiary.aidaemon.skills;

import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImageGenerationToolTest {

    private ImageModel mockModelReturning(byte[] imageBytes) {
        var imageModel = mock(ImageModel.class);
        var b64 = Base64.getEncoder().encodeToString(imageBytes);
        var image = mock(Image.class);
        when(image.getB64Json()).thenReturn(b64);
        var generation = mock(ImageGeneration.class);
        when(generation.getOutput()).thenReturn(image);
        var imageResponse = mock(ImageResponse.class);
        when(imageResponse.getResult()).thenReturn(generation);
        when(imageModel.call(any(ImagePrompt.class))).thenReturn(imageResponse);
        return imageModel;
    }

    private FileStorageService mockStorageReturning(String fileId) throws Exception {
        var fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.store(any(), any(), any(), any()))
                .thenReturn(new FileAttachment(fileId, "generated-image.png", "image/png"));
        return fileStorageService;
    }

    @Test
    void generateImage_storesImageAndReturnsMarkdownReference() throws Exception {
        var imageBytes = new byte[]{1, 2, 3, 4};
        var imageModel = mockModelReturning(imageBytes);
        var fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.store(eq("conv-1"), eq("generated-image.png"), eq("image/png"), eq(imageBytes)))
                .thenReturn(new FileAttachment("file-123", "generated-image.png", "image/png"));

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "conv-1");
        var result = tool.generateImage("a beautiful sunset over the ocean", null, null);

        assertTrue(result.contains("/api/files/file-123"), "Result should contain the file URL");
        verify(fileStorageService).store("conv-1", "generated-image.png", "image/png", imageBytes);
    }

    @Test
    void generateImage_returnsErrorMessage_whenImageModelThrows() {
        var imageModel = mock(ImageModel.class);
        var fileStorageService = mock(FileStorageService.class);
        when(imageModel.call(any(ImagePrompt.class))).thenThrow(new RuntimeException("API quota exceeded"));

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "conv-1");
        var result = tool.generateImage("a cat", null, null);

        assertTrue(result.toLowerCase().contains("failed") || result.toLowerCase().contains("error"),
                "Result should indicate failure");
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void generateImage_usesConversationIdForFileStorage() throws Exception {
        var imageModel = mockModelReturning(new byte[]{9, 8, 7});
        var fileStorageService = mockStorageReturning("id");

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "specific-conv-id");
        tool.generateImage("a dog", null, null);

        verify(fileStorageService).store(eq("specific-conv-id"), any(), any(), any());
    }

    @Test
    void generateImage_passesWidthAndHeightToModel() throws Exception {
        var imageModel = mockModelReturning(new byte[]{1, 2});
        var fileStorageService = mockStorageReturning("id");
        var captor = ArgumentCaptor.forClass(ImagePrompt.class);

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "conv-1");
        tool.generateImage("a wide landscape", 1792, 1024);

        verify(imageModel).call(captor.capture());
        var options = (OpenAiImageOptions) captor.getValue().getOptions();
        assertEquals(1792, options.getWidth());
        assertEquals(1024, options.getHeight());
    }

    @Test
    void generateImage_defaultsTo1024x1024_whenSizeIsNull() throws Exception {
        var imageModel = mockModelReturning(new byte[]{1, 2});
        var fileStorageService = mockStorageReturning("id");
        var captor = ArgumentCaptor.forClass(ImagePrompt.class);

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "conv-1");
        tool.generateImage("a cat", null, null);

        verify(imageModel).call(captor.capture());
        var options = (OpenAiImageOptions) captor.getValue().getOptions();
        assertEquals(1024, options.getWidth());
        assertEquals(1024, options.getHeight());
    }

    @Test
    void generateImage_returnsError_forUnsupportedSize() {
        var imageModel = mock(ImageModel.class);
        var fileStorageService = mock(FileStorageService.class);

        var tool = new ImageGenerationTool(fileStorageService, imageModel, "conv-1");
        var result = tool.generateImage("a cat", 512, 512);

        assertTrue(result.toLowerCase().contains("supported") || result.toLowerCase().contains("invalid"),
                "Result should indicate unsupported size");
        verifyNoInteractions(imageModel);
        verifyNoInteractions(fileStorageService);
    }
}
