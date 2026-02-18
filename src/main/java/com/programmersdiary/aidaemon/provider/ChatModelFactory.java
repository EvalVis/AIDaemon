package com.programmersdiary.aidaemon.provider;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

@Component
public class ChatModelFactory {

    public ChatModel create(ProviderConfig config) {
        return switch (config.type()) {
            case OPENAI -> createOpenAi(config);
            case ANTHROPIC -> createAnthropic(config);
            case OLLAMA -> createOllama(config);
            case GEMINI -> createGemini(config);
        };
    }

    private ChatModel createOpenAi(ProviderConfig config) {
        var apiBuilder = OpenAiApi.builder()
                .apiKey(config.apiKey() != null ? config.apiKey() : "unused");
        if (config.baseUrl() != null) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.model() != null ? config.model() : "gpt-4o")
                        .build())
                .build();
    }

    private ChatModel createAnthropic(ProviderConfig config) {
        var api = AnthropicApi.builder()
                .apiKey(config.apiKey())
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(config.model() != null ? config.model() : "claude-sonnet-4-20250514")
                        .maxTokens(4096)
                        .build())
                .build();
    }

    private ChatModel createGemini(ProviderConfig config) {
        var api = OpenAiApi.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl() != null ? config.baseUrl() : "https://generativelanguage.googleapis.com/v1beta/openai")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(config.model() != null ? config.model() : "gemini-2.0-flash")
                        .build())
                .build();
    }

    private ChatModel createOllama(ProviderConfig config) {
        var api = OllamaApi.builder()
                .baseUrl(config.baseUrl() != null ? config.baseUrl() : "http://localhost:11434")
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaChatOptions.builder()
                        .model(config.model() != null ? config.model() : "llama3.2")
                        .build())
                .build();
    }
}
