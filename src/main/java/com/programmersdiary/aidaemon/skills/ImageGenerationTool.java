package com.programmersdiary.aidaemon.skills;

import com.programmersdiary.aidaemon.files.FileStorageService;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Base64;

public class ImageGenerationTool {

    private final FileStorageService fileStorageService;
    private final ImageModel imageModel;
    private final String conversationId;

    public ImageGenerationTool(FileStorageService fileStorageService, ImageModel imageModel, String conversationId) {
        this.fileStorageService = fileStorageService;
        this.imageModel = imageModel;
        this.conversationId = conversationId;
    }

    @Tool(description = "Generate an image using DALL-E 3. Returns a markdown image reference to include in the answer so the user can see the image.")
    public String generateImage(
            @ToolParam(description = "Detailed description of the image to generate") String prompt) {
        try {
            var options = OpenAiImageOptions.builder()
                    .model("dall-e-3")
                    .responseFormat("b64_json")
                    .N(1)
                    .build();
            var response = imageModel.call(new ImagePrompt(prompt, options));
            var b64 = response.getResult().getOutput().getB64Json();
            var imageBytes = Base64.getDecoder().decode(b64);
            var attachment = fileStorageService.store(conversationId, "generated-image.png", "image/png", imageBytes);
            return "Image generated successfully. Include this markdown in your answer to display it to the user: "
                    + "![Generated image](/api/files/" + attachment.id() + ")";
        } catch (Exception e) {
            return "Image generation failed: " + e.getMessage();
        }
    }
}
