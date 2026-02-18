package com.programmersdiary.aidaemon.scheduling;

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
public class ScheduledJobRepository {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final Path jobsFile;
    private final List<ScheduledJob> jobs = new CopyOnWriteArrayList<>();

    public ScheduledJobRepository(
            @Value("${aidaemon.config-dir:${user.home}/.aidaemon}") String configDir) {
        this.jobsFile = Path.of(configDir, "jobs.json");
    }

    @PostConstruct
    void load() throws IOException {
        if (Files.exists(jobsFile)) {
            jobs.addAll(objectMapper.readValue(
                    jobsFile.toFile(), new TypeReference<List<ScheduledJob>>() {}));
        }
    }

    public List<ScheduledJob> findAll() {
        return List.copyOf(jobs);
    }

    public Optional<ScheduledJob> findById(String id) {
        return jobs.stream().filter(j -> j.id().equals(id)).findFirst();
    }

    public ScheduledJob save(ScheduledJob job) {
        jobs.removeIf(j -> j.id().equals(job.id()));
        jobs.add(job);
        persist();
        return job;
    }

    public boolean deleteById(String id) {
        boolean removed = jobs.removeIf(j -> j.id().equals(id));
        if (removed) {
            persist();
        }
        return removed;
    }

    private void persist() {
        try {
            Files.createDirectories(jobsFile.getParent());
            objectMapper.writeValue(jobsFile.toFile(), jobs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
