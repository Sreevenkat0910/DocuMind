package com.fruity.documind.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Stores uploaded files on the local filesystem and hands back an absolute path that is
 * persisted in {@code Document.storagePath}. Files are named by a random UUID so two
 * uploads with the same original filename never collide.
 *
 * <p>Local disk is fine for Phase 1 / a single-node demo. Swapping this for object storage
 * (S3/GCS) later only touches this class — nothing else knows where bytes physically live.
 */
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(@Value("${documind.storage.location:storage}") String location) {
        this.root = Paths.get(location).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not initialize storage directory: " + root, e);
        }
    }

    /**
     * Persist {@code content} under a fresh random filename with the given extension.
     * @return the absolute path the bytes were written to.
     */
    public String store(byte[] content, String extension) {
        String ext = extension.startsWith(".") ? extension : "." + extension;
        Path target = root.resolve(UUID.randomUUID() + ext);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file at " + target, e);
        }
        return target.toString();
    }
}
