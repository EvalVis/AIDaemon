package com.programmersdiary.aidaemon.web;

import com.programmersdiary.aidaemon.files.FileAttachment;
import com.programmersdiary.aidaemon.files.FileStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/conversations/{conversationId}/files")
public class FileController {

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileAttachment upload(@PathVariable String conversationId,
                                 @RequestPart("file") MultipartFile file) throws IOException {
        var mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        return fileStorageService.store(conversationId, file.getOriginalFilename(), mimeType, file.getBytes());
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String conversationId,
                                           @PathVariable String id) throws IOException {
        var attachment = fileStorageService.getAttachment(id);
        var bytes = fileStorageService.getBytes(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + attachment.name() + "\"")
                .contentType(MediaType.parseMediaType(attachment.mimeType()))
                .body(bytes);
    }
}
