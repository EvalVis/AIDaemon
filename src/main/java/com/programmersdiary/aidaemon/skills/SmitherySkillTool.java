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
    private final SmitheryClient smitheryClient;
    private final SkillsService skillsService;

    public SmitherySkillTool(SmitheryRegistryClient registryClient,
                              SmitheryClient smitheryClient,
                              SkillsService skillsService) {
        this.registryClient = registryClient;
        this.smitheryClient = smitheryClient;
        this.skillsService = skillsService;
    }

    @Tool(description = "Search the Smithery skills registry by keyword (e.g. gmail, github, code). Returns one page of results; use page parameter for more. Use installSmitherySkill to install a skill.")
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
                    + "\nTo install a skill, use installSmitherySkill with namespace and slug.";
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }

    @Tool(description = "Install a skill from Smithery by namespace and slug (e.g. namespace=smithery-ai, slug=skill-creator). Use searchSmitherySkills to find skills.")
    public String installSmitherySkill(
            @ToolParam(description = "Smithery namespace, e.g. smithery-ai or anthropics") String namespace,
            @ToolParam(description = "Skill slug, e.g. skill-creator") String slug) {
        if (namespace == null || namespace.isBlank() || slug == null || slug.isBlank()) {
            return "Provide both namespace and slug (e.g. from searchSmitherySkills results).";
        }
        try {
            var metadata = smitheryClient.getSkillMetadata(namespace.trim(), slug.trim());
            if (metadata == null || metadata.gitUrl() == null) {
                return "Skill not found: " + namespace + "/" + slug;
            }
            var files = smitheryClient.downloadSkillFiles(metadata.gitUrl());
            if (files.isEmpty()) {
                return "No files found for skill: " + namespace + "/" + slug;
            }
            skillsService.installSkill(slug.trim(), files);
            var name = metadata.displayName() != null ? metadata.displayName() : slug;
            return "Skill installed: " + name + " (" + slug + "). " + files.size() + " file(s).";
        } catch (Exception e) {
            return "Install failed: " + e.getMessage();
        }
    }

    @Tool(description = "Remove an installed skill by name. The skill folder and all its files are deleted.")
    public String removeSkill(
            @ToolParam(description = "Name of the skill folder to remove") String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return "Provide the skill name to remove.";
        }
        if (skillsService.removeSkill(skillName.trim())) {
            return "Skill removed: " + skillName;
        }
        return "Skill not found: " + skillName;
    }
}
