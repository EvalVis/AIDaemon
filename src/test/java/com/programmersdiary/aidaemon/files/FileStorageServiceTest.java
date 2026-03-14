package com.programmersdiary.aidaemon.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void store_savesFileAndReturnsAttachment() throws IOException {
        var service = new FileStorageService(tempDir.toString());
        var data = "hello world".getBytes();

        var attachment = service.store("test.txt", "text/plain", data);

        assertNotNull(attachment.id());
        assertEquals("test.txt", attachment.name());
        assertEquals("text/plain", attachment.mimeType());
    }

    @Test
    void store_savesFileWithExtension() throws IOException {
        var service = new FileStorageService(tempDir.toString());

        var attachment = service.store("image.png", "image/png", new byte[]{1, 2, 3});

        // The stored binary file should have the .png extension inside the uploads/ subdir
        var storedFile = tempDir.resolve("uploads").resolve(attachment.id() + ".png");
        assertTrue(storedFile.toFile().exists(), "Stored file should have .png extension");
    }

    @Test
    void store_fileWithNoExtension_savesWithoutExtension() throws IOException {
        var service = new FileStorageService(tempDir.toString());

        var attachment = service.store("Makefile", "text/plain", new byte[]{1});

        var storedFile = tempDir.resolve("uploads").resolve(attachment.id());
        assertTrue(storedFile.toFile().exists(), "File without extension stored as-is");
    }

    @Test
    void getBytes_returnsStoredBytes() throws IOException {
        var service = new FileStorageService(tempDir.toString());
        var data = new byte[]{1, 2, 3, 4};

        var attachment = service.store("image.png", "image/png", data);
        var retrieved = service.getBytes(attachment.id());

        assertArrayEquals(data, retrieved);
    }

    @Test
    void getAttachment_returnsMetadata() throws IOException {
        var service = new FileStorageService(tempDir.toString());

        var stored = service.store("doc.pdf", "application/pdf", new byte[]{10, 20});
        var retrieved = service.getAttachment(stored.id());

        assertEquals("doc.pdf", retrieved.name());
        assertEquals("application/pdf", retrieved.mimeType());
        assertEquals(stored.id(), retrieved.id());
    }

    @Test
    void getBytes_throwsForUnknownId() throws IOException {
        var service = new FileStorageService(tempDir.toString());
        assertThrows(IOException.class, () -> service.getBytes("nonexistent-id"));
    }

    @Test
    void getAttachment_throwsForUnknownId() throws IOException {
        var service = new FileStorageService(tempDir.toString());
        assertThrows(IOException.class, () -> service.getAttachment("nonexistent-id"));
    }
}