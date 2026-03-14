package com.programmersdiary.aidaemon.skills;

import com.programmersdiary.aidaemon.files.FileStorageService;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Base64;
import java.util.Set;

public class ImageGenerationTool {

    private static final Set<String> SUPPORTED_SIZES = Set.of("1024x1024", "1792x1024", "1024x1792");

    private final FileStorageService fileStorageService;
    private final ImageModel imageModel;
    private final String conversationId;

    public ImageGenerationTool(FileStorageService fileStorageService, ImageModel imageModel, String conversationId) {
        this.fileStorageService = fileStorageService;
        this.imageModel = imageModel;
        this.conversationId = conversationId;
    }

    @Tool(description = """
            Generate an image using DALL-E 3. Returns a markdown image reference to include in the answer.
            Supported sizes: 1024x1024 (default, square), 1792x1024 (landscape), 1024x1792 (portrait).
            """)
    public String generateImage(
            @ToolParam(description = "Detailed description of the image to generate") String prompt,
            @ToolParam(description = "Image width in pixels: 1024 or 1792. Defaults to 1024.", required = false) Integer width,
            @ToolParam(description = "Image height in pixels: 1024 or 1792. Defaults to 1024.", required = false) Integer height) {
        int w = width != null ? width : 1024;
        int h = height != null ? height : 1024;
        var sizeKey = w + "x" + h;
        if (!SUPPORTED_SIZES.contains(sizeKey)) {
            return "Invalid size " + sizeKey + ". Supported sizes: " + String.join(", ", SUPPORTED_SIZES);
        }
        try {
            var options = OpenAiImageOptions.builder()
                    .model("dall-e-3")
                    .responseFormat("b64_json")
                    .width(w)
                    .height(h)
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
