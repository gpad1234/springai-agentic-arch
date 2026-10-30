package com.example.springaigateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;

final class SecureFiles {

    private SecureFiles() {
    }

    /** Atomically writes a file that is only readable/writable by the current user (where POSIX is supported). */
    static void write(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp;
        try {
            FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            tmp = Files.createTempFile(dir, ".tmp-", ".part", ownerOnly);
        }
        catch (UnsupportedOperationException ex) {
            tmp = Files.createTempFile(dir, ".tmp-", ".part");
        }
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(tmp);
        }
    }
}
