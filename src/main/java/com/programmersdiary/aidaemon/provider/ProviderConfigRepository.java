package com.programmersdiary.aidaemon.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class ProviderConfigRepository {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path configFile;
    private final List<ProviderConfig> configs = new CopyOnWriteArrayList<>();

    public ProviderConfigRepository(
            @Value("${aidaemon.config-dir:${user.home}/.aidaemon}") String configDir) {
        this.configFile = Path.of(configDir, "providers.json");
    }

    @PostConstruct
    void load() throws IOException {
        if (Files.exists(configFile)) {
            configs.addAll(objectMapper.readValue(
                    configFile.toFile(), new TypeReference<List<ProviderConfig>>() {}));
        }
    }

    public List<ProviderConfig> findAll() {
        return List.copyOf(configs);
    }

    public Optional<ProviderConfig> findById(String id) {
        return configs.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public ProviderConfig save(ProviderConfig config) {
        configs.removeIf(c -> c.id().equals(config.id()));
        configs.add(config);
        persist();
        return config;
    }

    public boolean deleteById(String id) {
        boolean removed = configs.removeIf(c -> c.id().equals(id));
        if (removed) {
            persist();
        }
        return removed;
    }

    private void persist() {
        try {
            Files.createDirectories(configFile.getParent());
            objectMapper.writeValue(configFile.toFile(), configs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
