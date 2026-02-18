package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.provider.ProviderConfig;
import com.programmersdiary.aidaemon.provider.ProviderType;

public record ProviderResponse(String id, String name, ProviderType type, String baseUrl, String model) {

    static ProviderResponse from(ProviderConfig config) {
        return new ProviderResponse(config.id(), config.name(), config.type(), config.baseUrl(), config.model());
    }
}
