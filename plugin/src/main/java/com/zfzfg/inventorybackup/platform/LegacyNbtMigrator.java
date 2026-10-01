package com.zfzfg.inventorybackup.platform;

import java.io.*;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;

/** Converts the old metadata's BlockEntityTag before CraftMetaBlockState consumes it. */
public final class LegacyNbtMigrator {
    private static volatile Bridge bridge;
    private LegacyNbtMigrator() {}
    public static byte[] upgrade(byte[] bytes, int sourceVersion, int currentVersion, String material) {
        if (sourceVersion > currentVersion) throw new IllegalArgumentException("Cannot downgrade legacy metadata");
        NbtPayload.validate(bytes, currentVersion, false);
        try {
            Bridge selected = bridge;
            if (selected == null) {
                synchronized (LegacyNbtMigrator.class) {
                    if (bridge == null) bridge = new Bridge();
                    selected = bridge;
                }
            }
            return selected.upgrade(bytes, sourceVersion, currentVersion, material);
        } catch (ReflectiveOperationException error) { throw new IllegalArgumentException("Cannot migrate legacy block entity NBT", error); }
    }
    private static final class Bridge {
        final Method read, write, limit, update, getValue, get, optionalResult, put, putString;
        final Object fixer, reference, ops;
        final Constructor<?> dynamic;
        Bridge() throws ReflectiveOperationException {
            Class<?> tag = nms("net.minecraft.nbt.CompoundTag", "net.minecraft.nbt.NBTTagCompound");
            Class<?> base = nms("net.minecraft.nbt.Tag", "net.minecraft.nbt.NBTBase");
            Class<?> io = nms("net.minecraft.nbt.NbtIo", "net.minecraft.nbt.NBTCompressedStreamTools");
            Class<?> limiter = nms("net.minecraft.nbt.NbtAccounter", "net.minecraft.nbt.NBTReadLimiter");
            read = unique(io, m -> m.getReturnType() == tag && Arrays.equals(m.getParameterTypes(), new Class<?>[]{InputStream.class, limiter}));
            write = unique(io, m -> Arrays.equals(m.getParameterTypes(), new Class<?>[]{tag, OutputStream.class}));
            limit = unique(limiter, m -> Modifier.isStatic(m.getModifiers()) && m.getReturnType() == limiter
                    && Arrays.equals(m.getParameterTypes(), new Class<?>[]{long.class}));
            put = unique(tag, m -> Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class, base}));
            putString = unique(tag, m -> m.getReturnType() == void.class && Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class, String.class}));
            Class<?> opsType = nms("net.minecraft.nbt.NbtOps", "net.minecraft.nbt.DynamicOpsNBT");
            Object selectedOps = null;
            for (Field field : opsType.getFields())
                if (Modifier.isStatic(field.getModifiers()) && opsType.isAssignableFrom(field.getType())) selectedOps = field.get(null);
            if (selectedOps == null) throw new NoSuchFieldException("NBT ops singleton");
            ops = selectedOps;
            Class<?> dynamicType = Class.forName("com.mojang.serialization.Dynamic");
            dynamic = dynamicType.getConstructor(Class.forName("com.mojang.serialization.DynamicOps"), Object.class);
            getValue = dynamicType.getMethod("getValue");
            get = dynamicType.getMethod("get", String.class);
            optionalResult = Class.forName("com.mojang.serialization.OptionalDynamic").getMethod("result");
            Class<?> fixerType = Class.forName("com.mojang.datafixers.DataFixer");
            Class<?> fixers = nms("net.minecraft.util.datafix.DataFixers", "net.minecraft.util.datafix.DataConverterRegistry");
            fixer = NmsReflection.invoke(unique(fixers, m -> Modifier.isStatic(m.getModifiers()) && m.getReturnType() == fixerType && m.getParameterCount() == 0), null);
            Class<?> referenceType = Class.forName("com.mojang.datafixers.DSL$TypeReference");
            Class<?> references = nms("net.minecraft.util.datafix.fixes.References", "net.minecraft.util.datafix.fixes.DataConverterTypes");
            Object selectedReference = null;
            for (Field field : references.getFields())
                if (Modifier.isStatic(field.getModifiers()) && referenceType.isAssignableFrom(field.getType())) {
                    Object value = field.get(null);
                    if ("block_entity".equals(referenceType.getMethod("typeName").invoke(value))) selectedReference = value;
                }
            if (selectedReference == null) throw new NoSuchFieldException("BLOCK_ENTITY reference");
            reference = selectedReference;
            update = fixerType.getMethod("update", referenceType, dynamicType, int.class, int.class);
        }
        byte[] upgrade(byte[] bytes, int source, int current, String material) throws ReflectiveOperationException {
            Object root = NmsReflection.invoke(read, null, new ByteArrayInputStream(bytes), NmsReflection.invoke(limit, null, 16777216L));
            Object rootDynamic = dynamic.newInstance(ops, root);
            Optional<?> block = (Optional<?>) NmsReflection.invoke(optionalResult, NmsReflection.invoke(get, rootDynamic, "BlockEntityTag"));
            if (block.isEmpty()) return bytes;
            Object value = NmsReflection.invoke(getValue, block.get());
            if (material != null && material.endsWith("SHULKER_BOX"))
                NmsReflection.invoke(putString, value, "id", "minecraft:shulker_box");
            Object converted = NmsReflection.invoke(update, fixer, reference, dynamic.newInstance(ops, value), source, current);
            NmsReflection.invoke(put, root, "BlockEntityTag", NmsReflection.invoke(getValue, converted));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NmsReflection.invoke(write, null, root, output);
            byte[] result = output.toByteArray();
            NbtPayload.validate(result, current, false);
            return result;
        }
    }
    private static Class<?> nms(String mojang, String spigot) throws ClassNotFoundException {
        try { return Class.forName(mojang); } catch (ClassNotFoundException ignored) { return Class.forName(spigot); }
    }
    private static Method unique(Class<?> type, Predicate<Method> test) throws NoSuchMethodException {
        Method[] methods = Arrays.stream(type.getMethods()).filter(test).toArray(Method[]::new);
        if (methods.length != 1) throw new NoSuchMethodException("Ambiguous legacy NBT method on " + type.getName());
        return methods[0];
    }
}
