package com.zfzfg.inventorybackup.platform;

import org.bukkit.inventory.ItemStack;
import java.lang.reflect.Method;

/** Resolved only when the native Paper byte API is present. */
final class PaperItemCodec implements ItemCodec {
    private final Method encode;
    private final Method decode;
    private final int dataVersion;

    PaperItemCodec() throws ReflectiveOperationException {
        encode = ItemStack.class.getMethod("serializeAsBytes");
        decode = ItemStack.class.getMethod("deserializeBytes", byte[].class);
        dataVersion = MinecraftDataVersion.current();
    }
    @Override public byte[] encode(ItemStack item) {
        return (byte[]) NmsReflection.invoke(encode, item);
    }
    @Override public ItemStack decode(byte[] bytes) {
        NbtPayload.validate(bytes, dataVersion());
        return (ItemStack) NmsReflection.invoke(decode, null, (Object) bytes);
    }
    @Override public int dataVersion() { return dataVersion; }
}
