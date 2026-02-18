package com.programmersdiary.aidaemon.provider;

public record ProviderConfig(
        String id,
        String name,
        ProviderType type,
        String apiKey,
        String baseUrl,
        String model) {
}
