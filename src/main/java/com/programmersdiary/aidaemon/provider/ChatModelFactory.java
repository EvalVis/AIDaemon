package com.programmersdiary.aidaemon.provider;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicApi.ThinkingType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChatModelFactory {

    public ChatModel create(ProviderConfig config, List<ToolCallback> tools) {
        return switch (config.type()) {
            case OPENAI -> createOpenAi(config, tools);
            case ANTHROPIC -> createAnthropic(config, tools);
            case OLLAMA -> createOllama(config, tools);
            case GEMINI -> createGemini(config, tools);
        };
    }

    private ChatModel createOpenAi(ProviderConfig config, List<ToolCallback> tools) {
        var apiBuilder = OpenAiApi.builder()
                .apiKey(config.apiKey() != null ? config.apiKey() : "unused");
        if (config.baseUrl() != null) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        var options = OpenAiChatOptions.builder()
                .model(config.model() != null ? config.model() : "gpt-4o")
                .toolCallbacks(tools)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(options)
                .build();
    }

    private ChatModel createAnthropic(ProviderConfig config, List<ToolCallback> tools) {
        var api = AnthropicApi.builder()
                .apiKey(config.apiKey())
                .build();
        var maxTokens = 8192;
        var options = AnthropicChatOptions.builder()
                .model(config.model() != null ? config.model() : "claude-sonnet-4-20250514")
                .maxTokens(maxTokens)
                .thinking(ThinkingType.ENABLED, Math.min(4096, maxTokens - 1024))
                .temperature(1.0)
                .toolCallbacks(tools)
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .build();
    }

    private ChatModel createGemini(ProviderConfig config, List<ToolCallback> tools) {
        var api = OpenAiApi.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl() != null ? config.baseUrl() : "https://generativelanguage.googleapis.com/v1beta/openai")
                .build();
        var options = OpenAiChatOptions.builder()
                .model(config.model() != null ? config.model() : "gemini-2.0-flash")
                .toolCallbacks(tools)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    private ChatModel createOllama(ProviderConfig config, List<ToolCallback> tools) {
        var api = OllamaApi.builder()
                .baseUrl(config.baseUrl() != null ? config.baseUrl() : "http://localhost:11434")
                .build();
        var options = OllamaChatOptions.builder()
                .model(config.model() != null ? config.model() : "llama3.2")
                .toolCallbacks(tools)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .build();
    }
}
