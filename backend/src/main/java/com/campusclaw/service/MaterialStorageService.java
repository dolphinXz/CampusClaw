package com.campusclaw.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MaterialStorageService {

    private final Path root;

    public MaterialStorageService(@Value("${app.material.storage-dir}") String storageDir) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root);
    }

    public StoredFile store(String fileName, String contentType, byte[] bytes) throws IOException {
        String safeName = sanitize(fileName);
        String key = UUID.randomUUID() + "-" + safeName;
        Files.write(root.resolve(key), bytes);
        return new StoredFile(key, safeName, contentType, bytes.length);
    }

    public Path resolve(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return path;
    }

    public byte[] read(String key) throws IOException {
        return Files.readAllBytes(resolve(key));
    }

    private String sanitize(String fileName) {
        String safeName = fileName == null ? "material.bin" : fileName.replace('\\', '/');
        int slash = safeName.lastIndexOf('/');
        if (slash >= 0) {
            safeName = safeName.substring(slash + 1);
        }
        safeName = safeName.replaceAll("[^A-Za-z0-9._-]", "_");
        return safeName.isBlank() ? "material.bin" : safeName;
    }

    public record StoredFile(String key, String fileName, String contentType, long sizeBytes) {
    }
}
