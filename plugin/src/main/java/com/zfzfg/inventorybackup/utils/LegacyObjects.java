package com.zfzfg.inventorybackup.utils;

import com.zfzfg.inventorybackup.platform.LegacyNbtMigrator;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Defers Bukkit Wrapper resolution until the enclosing item's source version is known. */
final class LegacyObjects {
    record Serialized(Map<?, ?> values) {}
    private LegacyObjects() {}
    static BukkitObjectInputStream stream(InputStream source) throws IOException {
        return new BukkitObjectInputStream(source) {
            @Override protected Object resolveObject(Object value) throws IOException {
                if (value != null && value.getClass().getName().equals("org.bukkit.util.io.Wrapper")) {
                    try {
                        Field field = value.getClass().getDeclaredField("map"); field.setAccessible(true);
                        return new Serialized((Map<?, ?>) field.get(value));
                    } catch (ReflectiveOperationException error) { throw new IOException("Cannot read legacy Bukkit Wrapper", error); }
                }
                return value;
            }
        };
    }
    static ItemStack item(Object value) {
        ItemStack item = (ItemStack) resolve(value, -1, null, 0);
        if (item != null && !item.getType().isAir()) {
            // The map has now been translated by this server. Retaining the old
            // metadata version makes identical live items compare unequal.
            var meta = item.getItemMeta();
            if (meta != null) {
                meta.setVersion(com.zfzfg.inventorybackup.platform.Platform.currentDataVersion());
                item.setItemMeta(meta);
            }
        }
        return item;
    }
    private static Object resolve(Object value, int sourceVersion, String material, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Legacy object nesting/cycle limit exceeded");
        if (value instanceof Serialized serialized) {
            Map<?, ?> original = serialized.values();
            if ("org.bukkit.inventory.ItemStack".equals(original.get("==")) || "ItemStack".equals(original.get("=="))) {
                if (original.get("v") instanceof Number version) sourceVersion = version.intValue();
                material = (String) original.get("type");
            }
            Map<String, Object> map = new LinkedHashMap<>();
            for (var entry : original.entrySet()) map.put((String) entry.getKey(), resolve(entry.getValue(), sourceVersion, material, depth + 1));
            // Old Bukkit metadata decodes block entity NBT before ItemStack.setVersion.
            // Convert nested items first, while the original item's version is still available.
            if ("ItemMeta".equals(map.get("==")) && "TILE_ENTITY".equals(map.get("meta-type"))
                    && map.get("internal") instanceof String internal && sourceVersion > 0) {
                int current = com.zfzfg.inventorybackup.platform.Platform.currentDataVersion();
                if (sourceVersion < current) {
                    byte[] bytes = Base64.getMimeDecoder().decode(internal);
                    map.put("internal", Base64.getEncoder().encodeToString(
                            LegacyNbtMigrator.upgrade(bytes, sourceVersion, current, material)));
                }
            }
            Object result = ConfigurationSerialization.deserializeObject(map);
            if (result == null) throw new IllegalArgumentException("Invalid legacy Bukkit object");
            return result;
        }
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> map = new LinkedHashMap<>();
            for (var entry : values.entrySet()) map.put(entry.getKey(), resolve(entry.getValue(), sourceVersion, material, depth + 1));
            return map;
        }
        if (value instanceof List<?> values) {
            List<Object> list = new ArrayList<>(values.size());
            for (Object entry : values) list.add(resolve(entry, sourceVersion, material, depth + 1));
            return list;
        }
        return value;
    }
}
