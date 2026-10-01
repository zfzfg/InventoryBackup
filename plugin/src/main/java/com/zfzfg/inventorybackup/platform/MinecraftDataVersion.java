package com.zfzfg.inventorybackup.platform;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reads the running server's data version from Minecraft.
 * {@code Bukkit.getUnsafe()} is deprecated and has no replacement in the Bukkit API.
 */
final class MinecraftDataVersion {
    private MinecraftDataVersion() {}

    static int current() {
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            Object world = NmsReflection.invoke(unique(shared, method -> Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType().getName().endsWith("WorldVersion")), null);
            Object data = NmsReflection.invoke(unique(world.getClass(), method -> method.getParameterCount() == 0
                    && !method.isBridge()
                    && method.getReturnType().getName().endsWith("DataVersion")), world);
            return (int) NmsReflection.invoke(versionAccessor(data.getClass()), data);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot read Minecraft data version", error);
        }
    }

    private static Method versionAccessor(Class<?> type) throws NoSuchMethodException {
        Method named = null;
        Method only = null;
        int count = 0;
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class || method.isBridge() || method.getParameterCount() != 0
                    || method.getReturnType() != int.class) {
                continue;
            }
            count++;
            only = method;
            if (method.getName().equals("version") || method.getName().equals("getVersion")) named = method;
        }
        if (named != null) return named;
        if (count == 1) return only;
        throw new NoSuchMethodException("Data version accessor on " + type.getName());
    }

    private static Method unique(Class<?> type, java.util.function.Predicate<Method> test) throws NoSuchMethodException {
        Method match = null;
        for (Method method : type.getMethods()) {
            if (!test.test(method)) continue;
            if (match != null) throw new NoSuchMethodException("Ambiguous data version method on " + type.getName());
            match = method;
        }
        if (match == null) throw new NoSuchMethodException("Missing data version method on " + type.getName());
        return match;
    }
}
