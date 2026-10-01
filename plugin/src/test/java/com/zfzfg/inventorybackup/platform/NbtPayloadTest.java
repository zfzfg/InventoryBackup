package com.zfzfg.inventorybackup.platform;

import java.io.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NbtPayloadTest {
    private byte[] item(int version, boolean trailing) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10); out.writeUTF("");
            out.writeByte(3); out.writeUTF("DataVersion"); out.writeInt(version);
            out.writeByte(8); out.writeUTF("id"); out.writeUTF("minecraft:diamond");
            out.writeByte(0); if (trailing) out.writeByte(1);
        }
        return bytes.toByteArray();
    }
    @Test void acceptsCurrentAndOlderDataVersions() throws Exception {
        assertEquals(100, NbtPayload.validate(item(100, false), 101));
        assertEquals(101, NbtPayload.validate(item(101, false), 101));
    }
    @Test void rejectsDowngradesCorruptionAndTrailingData() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> NbtPayload.validate(item(102, false), 101));
        assertThrows(IllegalArgumentException.class, () -> NbtPayload.validate(item(0, false), 101));
        assertThrows(IllegalArgumentException.class, () -> NbtPayload.validate(item(100, true), 101));
        assertThrows(IllegalArgumentException.class, () -> NbtPayload.validate(new byte[]{1, 2, 3}, 101));
    }
    @Test void boundsExpandedPayload() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10); out.writeUTF("");
            out.writeByte(3); out.writeUTF("DataVersion"); out.writeInt(100);
            out.writeByte(7); out.writeUTF("large"); out.writeInt(16 * 1024 * 1024);
            byte[] chunk = new byte[8192];
            for (int i = 0; i < 2048; i++) out.write(chunk);
            out.writeByte(0);
        }
        assertTrue(bytes.size() < 100000); // A small compressed file still exceeds the expanded limit.
        assertThrows(IllegalArgumentException.class, () -> NbtPayload.validate(bytes.toByteArray(), 101));
    }
    @Test void parsesMinecraftVersionWithoutSnapshotSuffix() {
        assertEquals("1.21.8", Platform.minecraftVersion("1.21.8-R0.1-SNAPSHOT"));
        assertEquals("26.3", Platform.minecraftVersion("26.3-R0.1-SNAPSHOT"));
        assertEquals("26.1.2", Platform.minecraftVersion("26.1.2.build.74-stable"));
        assertEquals("26.2", Platform.minecraftVersion("26.2.build.128-stable"));
    }
}
