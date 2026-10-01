package com.zfzfg.inventorybackup.utils;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import java.io.*;
import java.util.Base64;

/** New writes use NBT; ObjectStream decoding is retained only for legacy archives. */
public final class InventorySerializer {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private InventorySerializer() {}
    public static String serializeInventory(ItemStack[] items) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(items.length);
                for (ItemStack item : items) {
                    byte[] value = item == null || item.getType().isAir() ? new byte[0] : item.serializeAsBytes();
                    out.writeInt(value.length); out.write(value);
                }
            }
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Inventory exceeds size limit");
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) { throw new IllegalArgumentException("Cannot encode inventory", e); }
    }
    public static String serializeItemStack(ItemStack item) {
        return item == null || item.getType().isAir() ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }
    private static byte[] bytes(String data) {
        if (data.length() > MAX_BYTES * 2) throw new IllegalArgumentException("Encoded item exceeds size limit");
        byte[] bytes = Base64.getMimeDecoder().decode(data);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Item exceeds size limit");
        return bytes;
    }
    public static ItemStack[] decodeInventory(String data, boolean nbt) {
        if (data == null || data.isEmpty()) throw new IllegalArgumentException("Missing inventory payload");
        try {
            ByteArrayInputStream bytes = new ByteArrayInputStream(bytes(data));
            if (!nbt) {
                try (BukkitObjectInputStream in = legacy(bytes)) {
                    int size = in.readInt(); checkSize(size);
                    ItemStack[] items = new ItemStack[size];
                    for (int i = 0; i < size; i++) items[i] = (ItemStack) in.readObject();
                    if (in.read() != -1) throw new IOException("Trailing inventory data");
                    return items;
                }
            }
            try (DataInputStream in = new DataInputStream(bytes)) {
                int size = in.readInt(); checkSize(size);
                ItemStack[] items = new ItemStack[size];
                for (int i = 0; i < size; i++) {
                    int length = in.readInt();
                    if (length < 0 || length > in.available()) throw new IOException("Invalid item length");
                    if (length > 0) items[i] = ItemStack.deserializeBytes(in.readNBytes(length));
                }
                if (in.available() != 0) throw new IOException("Trailing inventory data");
                return items;
            }
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            throw new IllegalArgumentException("Invalid inventory payload", e);
        }
    }
    private static void checkSize(int size) throws IOException {
        if (size < 0 || size > 41) throw new IOException("Invalid slot count: " + size);
    }
    private static BukkitObjectInputStream legacy(InputStream source) throws IOException {
        BukkitObjectInputStream in = new BukkitObjectInputStream(source);
        in.setObjectInputFilter(info -> {
            if (info.depth() > 32 || info.references() > 100000 || info.streamBytes() > MAX_BYTES
                    || info.arrayLength() > 100000) return ObjectInputFilter.Status.REJECTED;
            Class<?> type = info.serialClass();
            if (type == null) return ObjectInputFilter.Status.UNDECIDED;
            while (type.isArray()) type = type.getComponentType();
            String name = type.getName();
            return type.isPrimitive() || name.equals("org.bukkit.util.io.Wrapper")
                    || name.equals("org.bukkit.inventory.ItemStack")
                    || (name.startsWith("org.bukkit.craftbukkit.inventory.") &&
                        (ItemStack.class.isAssignableFrom(type) || org.bukkit.inventory.meta.ItemMeta.class.isAssignableFrom(type)))
                    || (name.startsWith("org.bukkit.") && type.isEnum())
                    || name.startsWith("java.lang.") || name.startsWith("java.util.")
                    || name.startsWith("com.google.common.collect.")
                    ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
        });
        return in;
    }
    public static ItemStack decodeItem(String data, boolean nbt) {
        if (data == null) throw new IllegalArgumentException("Missing offhand payload");
        if (data.isEmpty()) return null;
        if (nbt) return ItemStack.deserializeBytes(bytes(data));
        try (BukkitObjectInputStream in = legacy(new ByteArrayInputStream(bytes(data)))) {
            ItemStack item = (ItemStack) in.readObject();
            if (in.read() != -1) throw new IOException("Trailing item data");
            return item;
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            throw new IllegalArgumentException("Invalid item payload", e);
        }
    }
    public static ItemStack[] deserializeInventory(String data) {
        return data == null || data.isEmpty() ? new ItemStack[0] : decodeInventory(data, false);
    }
    public static ItemStack deserializeItemStack(String data) {
        return data == null || data.isEmpty() ? null : decodeItem(data, false);
    }
}
