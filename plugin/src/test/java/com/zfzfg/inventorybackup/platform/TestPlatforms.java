package com.zfzfg.inventorybackup.platform;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/** MockBukkit's byte serializer is a test surrogate, not compressed Minecraft NBT. */
public final class TestPlatforms {
    private TestPlatforms() {}
    public static Platform mock() {
        return new Platform(new ItemCodec() {
            public byte[] encode(ItemStack item) { return item.serializeAsBytes(); }
            public ItemStack decode(byte[] bytes) { return ItemStack.deserializeBytes(bytes); }
            public int dataVersion() { return Bukkit.getUnsafe().getDataVersion(); }
        }, true, "1.21.8");
    }
}
