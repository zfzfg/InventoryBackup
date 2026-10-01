package com.zfzfg.inventorybackup.utils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class AtomicFilesTest {
    @TempDir Path folder;
    @Test void replacementPreservesCompleteUtf8Document() throws Exception {
        Path file = folder.resolve("data.yml");
        AtomicFiles.write(file, "value: üäö\n");
        AtomicFiles.write(file, "value: replacement\n");
        assertEquals("value: replacement\n", Files.readString(file));
        try (var files = Files.list(folder)) { assertEquals(1, files.count()); }
    }
    @Test void failedReplacementPreservesExistingTarget() throws Exception {
        Path directory = folder.resolve("target"); Files.createDirectory(directory);
        Files.writeString(directory.resolve("keep"), "untouched");
        assertThrows(java.io.IOException.class, () -> AtomicFiles.write(directory, "replacement"));
        assertEquals("untouched", Files.readString(directory.resolve("keep")));
        try (var files = Files.list(folder)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))); }
    }
}
