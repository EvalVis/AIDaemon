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

    private final Path conversationsDir;

    public FileStorageService(
            @Value("${aidaemon.config-dir:#{systemProperties['user.home'] + '/.aidaemon'}}") String configDir) {
        this.conversationsDir = Path.of(configDir).resolve("conversations");
    }

    public FileAttachment store(String conversationId, String originalName, String mimeType, byte[] data) throws IOException {
        var id = UUID.randomUUID().toString();
        var filesDir = filesDir(conversationId);
        Files.createDirectories(filesDir);
        Files.write(binaryPath(filesDir, id, originalName), data);
        var attachment = new FileAttachment(id, originalName, mimeType);
        OBJECT_MAPPER.writeValue(filesDir.resolve(id + ".meta").toFile(), attachment);
        return attachment;
    }

    public byte[] getBytes(String id) throws IOException {
        var attachment = getAttachment(id);
        var filesDir = findFilesDir(id);
        var path = binaryPath(filesDir, id, attachment.name());
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + id);
        }
        return Files.readAllBytes(path);
    }

    public FileAttachment getAttachment(String id) throws IOException {
        var filesDir = findFilesDir(id);
        return OBJECT_MAPPER.readValue(filesDir.resolve(id + ".meta").toFile(), FileAttachment.class);
    }

    private Path findFilesDir(String id) throws IOException {
        if (Files.exists(conversationsDir)) {
            try (var stream = Files.list(conversationsDir)) {
                var found = stream
                        .filter(Files::isDirectory)
                        .map(dir -> dir.resolve("files"))
                        .filter(filesDir -> Files.exists(filesDir.resolve(id + ".meta")))
                        .findFirst();
                if (found.isPresent()) return found.get();
            }
        }
        throw new IOException("File metadata not found: " + id);
    }

    private Path filesDir(String conversationId) {
        return conversationsDir.resolve(conversationId).resolve("files");
    }

    private Path binaryPath(Path filesDir, String id, String originalName) {
        var ext = fileExtension(originalName);
        return filesDir.resolve(ext.isEmpty() ? id : id + "." + ext);
    }

    private static String fileExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }
}
