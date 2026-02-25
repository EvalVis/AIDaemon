package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "aidaemon.smithery-mcp.enabled", havingValue = "true")
public class SmitheryConnectClient {

    private static final String SMITHERY_BASE_URL = "https://api.smithery.ai";

    private final RestClient restClient;
    private final String apiKey;

    public SmitheryConnectClient(
            @org.springframework.beans.factory.annotation.Value("${aidaemon.smithery-mcp.api-key:}") String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
        this.restClient = RestClient.builder()
                .baseUrl(SMITHERY_BASE_URL)
                .defaultHeader("Authorization", "Bearer " + this.apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public ConnectResult createConnection(String namespace, String mcpUrl) {
        var body = new ConnectRequest(mcpUrl);
        var response = restClient.post()
                .uri("/connect/{namespace}", namespace)
                .body(body)
                .retrieve()
                .body(ConnectResponse.class);
        if (response == null) {
            throw new IllegalStateException("Smithery Connect API returned empty response");
        }
        var state = response.status() != null ? response.status().state() : null;
        var authUrl = response.status() != null ? response.status().authorizationUrl() : null;
        if (authUrl == null && response.authorizationUrl() != null) {
            authUrl = response.authorizationUrl();
        }
        return new ConnectResult(response.connectionId(), state, authUrl);
    }

    private record ConnectRequest(String mcpUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConnectResponse(
            @JsonProperty("connectionId") String connectionId,
            @JsonProperty("status") ConnectStatus status,
            @JsonProperty("authorizationUrl") String authorizationUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ConnectStatus(
            @JsonProperty("state") String state,
            @JsonProperty("authorizationUrl") String authorizationUrl) {}

    public record ConnectResult(String connectionId, String state, String authorizationUrl) {}
}
