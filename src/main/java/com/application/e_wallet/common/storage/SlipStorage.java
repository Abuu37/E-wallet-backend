package com.application.e_wallet.common.storage;

import com.application.e_wallet.common.exception.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Keeps deposit slip photos on disk under a random name. The type is decided from the file's first bytes, never from
 * the name or the type the browser claims, and only a photo or a PDF up to 5 MB is accepted.
 */
@Component
public class SlipStorage {

    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public record Stored(String key, String contentType) { }

    public record Loaded(byte[] bytes, String contentType) { }

    private final Path dir;

    public SlipStorage(@Value("${app.uploads.dir:./uploads}") String dir) {
        this.dir = Path.of(dir).toAbsolutePath().normalize().resolve("slips");
    }

    public Stored save(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("Attach a photo of the deposit slip.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BadRequestException("The slip photo is too large. The most is 5 MB.");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new BadRequestException("The slip must be a JPG or PNG photo, or a PDF.");
        }
        String key = UUID.randomUUID().toString().replace("-", "") + extension(type);
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(key), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the slip", e);
        }
        return new Stored(key, type);
    }

    public Loaded load(String key, String contentType) {
        if (key == null || !key.matches("[0-9a-f]{32}\\.(jpg|png|pdf)")) {
            throw new BadRequestException("Slip not found");
        }
        try {
            return new Loaded(Files.readAllBytes(dir.resolve(key)), contentType);
        } catch (IOException e) {
            throw new BadRequestException("Slip not found");
        }
    }

    static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        return null;
    }

    private static String extension(String type) {
        return switch (type) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".pdf";
        };
    }
}
