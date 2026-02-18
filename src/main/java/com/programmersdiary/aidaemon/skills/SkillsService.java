package com.programmersdiary.aidaemon.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SkillsService {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path configDir;
    private final Path skillsDir;
    private final Path memoryFile;

    public SkillsService(
            @Value("${aidaemon.config-dir:${user.home}/.aidaemon}") String configDir) {
        this.configDir = Path.of(configDir);
        this.skillsDir = this.configDir.resolve("skills");
        this.memoryFile = this.configDir.resolve("memory.json");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(skillsDir);
        if (!Files.exists(memoryFile)) {
            objectMapper.writeValue(memoryFile.toFile(), new LinkedHashMap<>());
        }
    }

    public Map<String, String> readMemory() {
        try {
            return objectMapper.readValue(memoryFile.toFile(),
                    new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void saveMemory(String key, String value) {
        var memory = readMemory();
        memory.put(key, value);
        try {
            objectMapper.writeValue(memoryFile.toFile(), memory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readFile(String relativePath) {
        var path = skillsDir.resolve(relativePath).normalize();
        if (!path.startsWith(skillsDir)) {
            return "Error: Cannot read files outside the skills folder.";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "Error: File not found: " + relativePath;
        }
    }

    public List<String> listSkills() {
        try (var stream = Files.list(skillsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public List<String> listSkillFiles(String skillName) {
        var skillDir = skillsDir.resolve(skillName).normalize();
        if (!skillDir.startsWith(skillsDir) || !Files.isDirectory(skillDir)) {
            return List.of();
        }
        try (var stream = Files.walk(skillDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> skillsDir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public List<String> listAllFiles() {
        var result = new ArrayList<String>();
        for (var skill : listSkills()) {
            result.addAll(listSkillFiles(skill));
        }
        return result;
    }
}
