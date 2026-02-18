package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.provider.ProviderType;

public record CreateProviderRequest(
        String name,
        ProviderType type,
        String apiKey,
        String baseUrl,
        String model) {
}
