package com.zfzfg.inventorybackup.platform;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import java.io.*;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * CraftBukkit NBT bridge for the explicitly enumerated 1.21.8–26.3 targets.
 * Uses Minecraft's registry-aware ItemStack codec and ITEM_STACK data fixer.
 * Mojang and Spigot member names are resolved once; ambiguous signatures fail closed.
 */
final class SpigotItemCodec implements ItemCodec {
    private final Object codec, ops, nbtOps, dataFixer, itemReference;
    private final Method asNms, asBukkit, encodeStart, parse, getOrThrow, putInt;
    private final Method readCompressed, writeCompressed, limiter, update, dynamicValue;
    private final Constructor<?> dynamic;

    SpigotItemCodec(String version) throws ReflectiveOperationException {
        if (!Platform.TARGET_VERSIONS.contains(version)) throw new IllegalArgumentException("Unsupported Spigot " + version);
        String craft = Bukkit.getServer().getClass().getPackageName();
        Class<?> craftStack = Class.forName(craft + ".inventory.CraftItemStack");
        asNms = craftStack.getMethod("asNMSCopy", ItemStack.class);
        Class<?> stack = asNms.getReturnType();
        asBukkit = craftStack.getMethod("asBukkitCopy", stack);
        Class<?> codecType = Class.forName("com.mojang.serialization.Codec");
        codec = field(stack, codecType, "CODEC", "b");
        Class<?> opsType = Class.forName("com.mojang.serialization.DynamicOps");
        Class<?> nbtOpsType = nms("net.minecraft.nbt.NbtOps", "net.minecraft.nbt.DynamicOpsNBT");
        nbtOps = field(nbtOpsType, nbtOpsType, "INSTANCE", "a");
        Object registry = Class.forName(craft + ".CraftRegistry").getMethod("getMinecraftRegistry").invoke(null);
        Class<?> lookup = nms("net.minecraft.core.HolderLookup$Provider", "net.minecraft.core.HolderLookup$a");
        Method context = unique(lookup, m -> Arrays.equals(m.getParameterTypes(), new Class<?>[]{opsType})
                && m.getReturnType().getName().equals("net.minecraft.resources.RegistryOps"));
        ops = NmsReflection.invoke(context, registry, nbtOps);
        encodeStart = codecType.getMethod("encodeStart", opsType, Object.class);
        parse = codecType.getMethod("parse", opsType, Object.class);
        getOrThrow = Class.forName("com.mojang.serialization.DataResult").getMethod("getOrThrow");
        Class<?> compound = nms("net.minecraft.nbt.CompoundTag", "net.minecraft.nbt.NBTTagCompound");
        putInt = unique(compound, m -> m.getReturnType() == void.class
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class, int.class}));
        Class<?> nbtIo = nms("net.minecraft.nbt.NbtIo", "net.minecraft.nbt.NBTCompressedStreamTools");
        Class<?> accounter = nms("net.minecraft.nbt.NbtAccounter", "net.minecraft.nbt.NBTReadLimiter");
        readCompressed = unique(nbtIo, m -> Modifier.isStatic(m.getModifiers())
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{InputStream.class, accounter})
                && m.getReturnType() == compound);
        writeCompressed = unique(nbtIo, m -> Modifier.isStatic(m.getModifiers())
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{compound, OutputStream.class}));
        limiter = unique(accounter, m -> Modifier.isStatic(m.getModifiers()) && m.getReturnType() == accounter
                && Arrays.equals(m.getParameterTypes(), new Class<?>[]{long.class}));
        Class<?> fixerType = Class.forName("com.mojang.datafixers.DataFixer");
        Class<?> fixers = nms("net.minecraft.util.datafix.DataFixers", "net.minecraft.util.datafix.DataConverterRegistry");
        dataFixer = NmsReflection.invoke(unique(fixers, m -> Modifier.isStatic(m.getModifiers())
                && m.getReturnType() == fixerType && m.getParameterCount() == 0), null);
        Class<?> referenceType = Class.forName("com.mojang.datafixers.DSL$TypeReference");
        Class<?> references = nms("net.minecraft.util.datafix.fixes.References", "net.minecraft.util.datafix.fixes.DataConverterTypes");
        Object reference = null;
        for (Field f : references.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && referenceType.isAssignableFrom(f.getType())) {
                Object value = f.get(null);
                if ("item_stack".equals(referenceType.getMethod("typeName").invoke(value))) {
                    if (reference != null) throw new NoSuchFieldException("Ambiguous ITEM_STACK reference");
                    reference = value;
                }
            }
        }
        if (reference == null) throw new NoSuchFieldException("Missing ITEM_STACK reference");
        itemReference = reference;
        Class<?> dynamicType = Class.forName("com.mojang.serialization.Dynamic");
        dynamic = dynamicType.getConstructor(opsType, Object.class);
        dynamicValue = dynamicType.getMethod("getValue");
        update = fixerType.getMethod("update", referenceType, dynamicType, int.class, int.class);
    }
    @Override public byte[] encode(ItemStack item) {
        Object tag = NmsReflection.invoke(getOrThrow,
                NmsReflection.invoke(encodeStart, codec, ops, NmsReflection.invoke(asNms, null, item)));
        NmsReflection.invoke(putInt, tag, "DataVersion", dataVersion());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NmsReflection.invoke(writeCompressed, null, tag, bytes);
        byte[] result = bytes.toByteArray();
        NbtPayload.validate(result, dataVersion());
        return result;
    }
    @Override public ItemStack decode(byte[] bytes) {
        int sourceVersion = NbtPayload.validate(bytes, dataVersion());
        Object tag = NmsReflection.invoke(readCompressed, null, new ByteArrayInputStream(bytes),
                NmsReflection.invoke(limiter, null, (long) com.zfzfg.inventorybackup.utils.InventorySerializer.MAX_BYTES));
        if (sourceVersion < dataVersion()) {
            try {
                Object converted = NmsReflection.invoke(update, dataFixer, itemReference,
                        dynamic.newInstance(nbtOps, tag), sourceVersion, dataVersion());
                tag = NmsReflection.invoke(dynamicValue, converted);
            } catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Item data conversion failed", error); }
        }
        Object stack = NmsReflection.invoke(getOrThrow, NmsReflection.invoke(parse, codec, ops, tag));
        return (ItemStack) NmsReflection.invoke(asBukkit, null, stack);
    }
    @Override public int dataVersion() { return Bukkit.getUnsafe().getDataVersion(); }

    private static Class<?> nms(String mojang, String spigot) throws ClassNotFoundException {
        try { return Class.forName(mojang); } catch (ClassNotFoundException ignored) { return Class.forName(spigot); }
    }
    private static Object field(Class<?> owner, Class<?> expected, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            try {
                Field field = owner.getField(name);
                if (Modifier.isStatic(field.getModifiers()) && expected.isAssignableFrom(field.getType())) return field.get(null);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner.getName() + " " + Arrays.toString(names));
    }
    private static Method unique(Class<?> owner, Predicate<Method> predicate) throws NoSuchMethodException {
        Method[] matches = Arrays.stream(owner.getMethods()).filter(predicate).toArray(Method[]::new);
        if (matches.length != 1) throw new NoSuchMethodException("Expected one adapter method on " + owner.getName()
                + ", found " + Arrays.toString(matches));
        return matches[0];
    }
}
