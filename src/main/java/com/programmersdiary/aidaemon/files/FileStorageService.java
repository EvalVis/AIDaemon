package com.programmersdiary.aidaemon.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path uploadsDir;

    public FileStorageService(
            @Value("${aidaemon.config-dir:#{systemProperties['user.home'] + '/.aidaemon'}}") String configDir) throws IOException {
        this.uploadsDir = Path.of(configDir).resolve("uploads");
        Files.createDirectories(uploadsDir);
    }

    public FileAttachment store(String originalName, String mimeType, byte[] data) throws IOException {
        var id = UUID.randomUUID().toString();
        Files.write(binaryPath(id, originalName), data);
        var attachment = new FileAttachment(id, originalName, mimeType);
        OBJECT_MAPPER.writeValue(uploadsDir.resolve(id + ".meta").toFile(), attachment);
        return attachment;
    }

    public byte[] getBytes(String id) throws IOException {
        var attachment = getAttachment(id);
        var path = binaryPath(id, attachment.name());
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + id);
        }
        return Files.readAllBytes(path);
    }

    public FileAttachment getAttachment(String id) throws IOException {
        var metaPath = uploadsDir.resolve(id + ".meta");
        if (!Files.exists(metaPath)) {
            throw new IOException("File metadata not found: " + id);
        }
        return OBJECT_MAPPER.readValue(metaPath.toFile(), FileAttachment.class);
    }

    private Path binaryPath(String id, String originalName) {
        var ext = fileExtension(originalName);
        return uploadsDir.resolve(ext.isEmpty() ? id : id + "." + ext);
    }

    private static String fileExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }
}
