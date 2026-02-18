package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.skills.SkillsService;
import com.programmersdiary.aidaemon.skills.SmitheryClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillsService skillsService;
    private final SmitheryClient smitheryClient;

    public SkillController(SkillsService skillsService, SmitheryClient smitheryClient) {
        this.skillsService = skillsService;
        this.smitheryClient = smitheryClient;
    }

    @GetMapping
    public List<String> listSkills() {
        return skillsService.listSkills();
    }

    @GetMapping("/{skillName}/files")
    public List<String> listFiles(@PathVariable String skillName) {
        return skillsService.listSkillFiles(skillName);
    }

    @PostMapping("/install/{namespace}/{slug}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> installFromSmithery(@PathVariable String namespace, @PathVariable String slug) {
        var skill = smitheryClient.getSkill(namespace, slug);
        if (skill == null || skill.prompt() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found: " + namespace + "/" + slug);
        }
        skillsService.installSkill(slug, skill.prompt());
        return Map.of(
                "skill", slug,
                "name", skill.displayName(),
                "description", skill.description(),
                "source", "smithery:" + namespace + "/" + slug);
    }

    @DeleteMapping("/{skillName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSkill(@PathVariable String skillName) {
        if (!skillsService.removeSkill(skillName)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
