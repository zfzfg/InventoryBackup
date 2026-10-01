package com.zfzfg.inventorybackup.platform;

import com.zfzfg.inventorybackup.utils.InventorySerializer;
import java.io.*;
import java.util.zip.GZIPInputStream;

/** Bounds compressed NBT before handing it to a server parser or data fixer. */
final class NbtPayload {
    private NbtPayload() {}
    static int validate(byte[] bytes, int currentVersion) {
        return validate(bytes, currentVersion, true);
    }
    static int validate(byte[] bytes, int currentVersion, boolean requireVersion) {
        if (bytes.length == 0 || bytes.length > InventorySerializer.MAX_BYTES)
            throw new IllegalArgumentException("Invalid compressed NBT size");
        try (DataInputStream in = new DataInputStream(new BoundedInput(new GZIPInputStream(
                new ByteArrayInputStream(bytes))))) {
            if (in.readUnsignedByte() != 10) throw new IOException("Expected root compound");
            in.readUTF();
            int version = -1;
            for (int type; (type = in.readUnsignedByte()) != 0;) {
                String name = in.readUTF();
                if (name.equals("DataVersion")) {
                    if (type != 3 || version != -1) throw new IOException("Invalid DataVersion");
                    version = in.readInt();
                } else skip(in, type, 1);
            }
            if (in.read() != -1) throw new IOException("Trailing NBT data");
            if ((requireVersion && version <= 0) || version > currentVersion) throw new IOException("Unsupported DataVersion: " + version);
            return version;
        } catch (IOException error) { throw new IllegalArgumentException("Invalid item NBT", error); }
    }
    private static int length(DataInputStream in, int width) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > InventorySerializer.MAX_BYTES / width)
            throw new IOException("Invalid NBT collection size");
        return length;
    }
    private static void skip(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nesting limit exceeded");
        switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3, 5 -> in.readInt();
            case 4, 6 -> in.readLong();
            case 7 -> in.skipNBytes(length(in, 1));
            case 8 -> in.readUTF();
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int count = length(in, 1);
                if (elementType == 0 && count != 0) throw new IOException("Non-empty end-tag list");
                for (int i = 0; i < count; i++) skip(in, elementType, depth + 1);
            }
            case 10 -> {
                for (int child; (child = in.readUnsignedByte()) != 0;) {
                    in.readUTF(); skip(in, child, depth + 1);
                }
            }
            case 11 -> in.skipNBytes((long) length(in, 4) * 4);
            case 12 -> in.skipNBytes((long) length(in, 8) * 8);
            default -> throw new IOException("Unknown NBT tag: " + type);
        }
    }
    private static final class BoundedInput extends FilterInputStream {
        private long remaining = InventorySerializer.MAX_BYTES;
        BoundedInput(InputStream input) { super(input); }
        private void count(long n) throws IOException {
            if (n > 0 && (remaining -= n) < 0) throw new IOException("Expanded NBT exceeds size limit");
        }
        @Override public int read() throws IOException {
            int value = super.read(); if (value != -1) count(1); return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int n = in.read(bytes, offset, (int) Math.min(length, remaining + 1)); count(n); return n;
        }
        @Override public long skip(long n) throws IOException {
            long skipped = in.skip(Math.min(n, remaining + 1)); count(skipped); return skipped;
        }
    }
}
