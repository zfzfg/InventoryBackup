package com.zfzfg.inventorybackup.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Durable replacement with a recoverable previous version on non-atomic filesystems. */
public final class AtomicFiles {
    private AtomicFiles() {}
    public static void write(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".inventorybackup-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Path previous = target.resolveSibling(target.getFileName() + ".previous");
                if (Files.exists(target)) Files.copy(target, previous, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException failure) {
                    if (Files.exists(previous)) Files.copy(previous, target, StandardCopyOption.REPLACE_EXISTING);
                    throw failure;
                }
            }
        } finally { Files.deleteIfExists(temp); }
    }
}
