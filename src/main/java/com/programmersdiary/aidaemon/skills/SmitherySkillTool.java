package com.programmersdiary.aidaemon.skills;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@ConditionalOnBean(SmitheryRegistryClient.class)
public class SmitherySkillTool {

    private static final int SEARCH_PAGE_SIZE = 10;

    private final SmitheryRegistryClient registryClient;

    public SmitherySkillTool(SmitheryRegistryClient registryClient) {
        this.registryClient = registryClient;
    }

    @Tool(description = "Search the Smithery skills registry by keyword (e.g. gmail, github, code). Returns one page of results; use page parameter for more. To install one use POST /api/skills/install/{namespace}/{slug}.")
    public String searchSmitherySkills(
            @ToolParam(description = "Search query, e.g. gmail, github, productivity") String query,
            @ToolParam(description = "Page number (1-based). Omit for first page.") Integer page) {
        if (query == null || query.isBlank()) {
            return "Provide a search query (e.g. gmail, github).";
        }
        int pageNum = page == null || page < 1 ? 1 : page;
        try {
            var response = registryClient.searchSkills(query.trim(), pageNum, SEARCH_PAGE_SIZE);
            if (response == null || response.skills() == null || response.skills().length == 0) {
                return "No skills found for \"" + query + "\" on page " + pageNum + ".";
            }
            var pagination = response.pagination();
            var lines = IntStream.range(0, response.skills().length)
                    .mapToObj(i -> {
                        var s = response.skills()[i];
                        return (i + 1) + ". " + s.displayName() + " — " + (s.description() != null ? s.description() : "")
                                + " Install: " + s.namespace() + "/" + s.slug();
                    })
                    .collect(Collectors.joining("\n"));
            var pageHint = pagination.totalPages() > 1
                    ? " Page " + pagination.currentPage() + " of " + pagination.totalPages() + ". For more, call searchSmitherySkills with the same query and page=" + (pageNum + 1) + "."
                    : "";
            return "Found " + pagination.totalCount() + " skill(s)." + pageHint + "\n" + lines
                    + "\nTo install: POST /api/skills/install/{namespace}/{slug}";
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }
}
