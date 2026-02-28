package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "aidaemon.smithery-mcp.enabled", havingValue = "true")
public class SmitheryRegistryClient {

    private static final String SMITHERY_BASE_URL = "https://api.smithery.ai";

    private final RestClient restClient;

    public SmitheryRegistryClient(
            @org.springframework.beans.factory.annotation.Value("${aidaemon.smithery-mcp.api-key:}") String apiKey) {
        var key = apiKey != null ? apiKey : "";
        this.restClient = RestClient.builder()
                .baseUrl(SMITHERY_BASE_URL)
                .defaultHeader("Authorization", "Bearer " + key)
                .build();
    }

    public ServersListResponse searchServers(String query, int page, int pageSize) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/servers")
                        .queryParam("q", query != null ? query : "")
                        .queryParam("page", page)
                        .queryParam("pageSize", pageSize)
                        .build())
                .retrieve()
                .body(ServersListResponse.class);
    }

    public SkillsListResponse searchSkills(String query, int page, int pageSize) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/skills")
                        .queryParam("q", query != null ? query : "")
                        .queryParam("page", page)
                        .queryParam("pageSize", pageSize)
                        .build())
                .retrieve()
                .body(SkillsListResponse.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServersListResponse(
            @JsonProperty("servers") ServerSummary[] servers,
            @JsonProperty("pagination") Pagination pagination) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(
            @JsonProperty("currentPage") int currentPage,
            @JsonProperty("pageSize") int pageSize,
            @JsonProperty("totalPages") int totalPages,
            @JsonProperty("totalCount") int totalCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerSummary(
            @JsonProperty("id") String id,
            @JsonProperty("qualifiedName") String qualifiedName,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("slug") String slug,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("description") String description,
            @JsonProperty("homepage") String homepage,
            @JsonProperty("isDeployed") boolean isDeployed,
            @JsonProperty("remote") Boolean remote) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillsListResponse(
            @JsonProperty("skills") SkillSummary[] skills,
            @JsonProperty("pagination") Pagination pagination) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillSummary(
            @JsonProperty("id") String id,
            @JsonProperty("namespace") String namespace,
            @JsonProperty("slug") String slug,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("description") String description,
            @JsonProperty("gitUrl") String gitUrl,
            @JsonProperty("verified") boolean verified) {}
}
