package com.zfzfg.inventorybackup.platform;

import org.bukkit.inventory.ItemStack;

/** MockBukkit's byte serializer is a test surrogate, not compressed Minecraft NBT. */
public final class TestPlatforms {
    /** MockBukkit has no Minecraft data version. Archive metadata in unit tests only needs a positive value. */
    private static final int MOCK_DATA_VERSION = 1;
    private TestPlatforms() {}
    public static Platform mock() {
        return new Platform(new ItemCodec() {
            public byte[] encode(ItemStack item) { return item.serializeAsBytes(); }
            public ItemStack decode(byte[] bytes) { return ItemStack.deserializeBytes(bytes); }
            public int dataVersion() { return MOCK_DATA_VERSION; }
        }, true, "1.21.8");
    }
}
