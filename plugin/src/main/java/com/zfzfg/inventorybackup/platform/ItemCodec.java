package com.zfzfg.inventorybackup.platform;

import org.bukkit.inventory.ItemStack;

/** Internal lossless, compressed item NBT codec. Call only on the server thread. */
public interface ItemCodec {
    byte[] encode(ItemStack item);
    ItemStack decode(byte[] bytes);
    int dataVersion();
}
