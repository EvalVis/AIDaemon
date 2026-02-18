package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class SmitheryClient {

    private static final String SMITHERY_BASE_URL = "https://api.smithery.ai";
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final Pattern GIT_URL_PATTERN =
            Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+)");

    private final RestClient smitheryClient;
    private final RestClient githubClient;

    public SmitheryClient() {
        this.smitheryClient = RestClient.builder()
                .baseUrl(SMITHERY_BASE_URL)
                .build();
        this.githubClient = RestClient.builder()
                .baseUrl(GITHUB_API_BASE)
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build();
    }

    public SkillMetadata getSkillMetadata(String namespace, String slug) {
        return smitheryClient.get()
                .uri("/skills/{namespace}/{slug}", namespace, slug)
                .retrieve()
                .body(SkillMetadata.class);
    }

    public Map<String, String> downloadSkillFiles(String gitUrl) {
        var matcher = GIT_URL_PATTERN.matcher(gitUrl);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Cannot parse gitUrl: " + gitUrl);
        }
        var owner = matcher.group(1);
        var repo = matcher.group(2);
        var ref = matcher.group(3);
        var basePath = matcher.group(4);

        var files = new LinkedHashMap<String, String>();
        downloadRecursive(owner, repo, ref, basePath, basePath, files);
        return files;
    }

    private void downloadRecursive(String owner, String repo, String ref,
                                   String currentPath, String basePath,
                                   Map<String, String> files) {
        var items = githubClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}?ref={ref}",
                        owner, repo, currentPath, ref)
                .retrieve()
                .body(GithubContent[].class);

        if (items == null) return;

        for (var item : items) {
            if ("file".equals(item.type())) {
                var content = downloadFileContent(owner, repo, ref, item.path());
                if (content != null) {
                    var relativePath = item.path().substring(basePath.length() + 1);
                    files.put(relativePath, content);
                }
            } else if ("dir".equals(item.type())) {
                downloadRecursive(owner, repo, ref, item.path(), basePath, files);
            }
        }
    }

    private String downloadFileContent(String owner, String repo, String ref, String path) {
        var file = githubClient.get()
                .uri("/repos/{owner}/{repo}/contents/{path}?ref={ref}",
                        owner, repo, path, ref)
                .retrieve()
                .body(GithubFileContent.class);

        if (file == null || file.content() == null) return null;

        var cleaned = file.content().replaceAll("\\s", "");
        return new String(Base64.getDecoder().decode(cleaned));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillMetadata(
            @JsonProperty("namespace") String namespace,
            @JsonProperty("slug") String slug,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("description") String description,
            @JsonProperty("gitUrl") String gitUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GithubContent(
            @JsonProperty("name") String name,
            @JsonProperty("path") String path,
            @JsonProperty("type") String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GithubFileContent(
            @JsonProperty("content") String content,
            @JsonProperty("encoding") String encoding) {
    }
}
