package com.zfzfg.inventorybackup.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class NmsReflection {
    private NmsReflection() {}
    static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalArgumentException("NBT adapter call failed: " + method, error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("NBT adapter call failed: " + method, error);
        }
    }
}
