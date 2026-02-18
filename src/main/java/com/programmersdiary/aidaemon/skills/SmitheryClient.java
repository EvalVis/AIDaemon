package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class SmitheryClient {

    private static final String BASE_URL = "https://api.smithery.ai";

    private final RestClient restClient;

    public SmitheryClient() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    public SkillResponse getSkill(String namespace, String slug) {
        return restClient.get()
                .uri("/skills/{namespace}/{slug}", namespace, slug)
                .retrieve()
                .body(SkillResponse.class);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillResponse(
            @JsonProperty("namespace") String namespace,
            @JsonProperty("slug") String slug,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("description") String description,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("categories") List<String> categories,
            @JsonProperty("servers") List<String> servers,
            @JsonProperty("gitUrl") String gitUrl) {
    }
}
