package com.cloudmusictool.app.profile;

import android.util.Log;

import com.cloudmusictool.app.dexkit.DexKitLocator;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把 MountSpec 解析成真实的 Class/Method。
 *
 * 顺序：精确类名 -> DexKit 结构/锚点兜底 -> 失败返回 null。
 * 所有结果都做包名/成员/签名校验；多候选无法消歧时拒绝，不猜。
 *
 * 缓存按 ClassLoader 隔离：同一个 spec 在 base ClassLoader 与 Tinker 运行时
 * ClassLoader 下必须解析到各自的 Class/Method，不能互相污染。
 *
 * 结构：WeakHashMap&lt;ClassLoader, Map&lt;specId, WeakReference&lt;T&gt;&gt;&gt;，
 * null ClassLoader 用固定哨兵。ClassLoader 是弱键；缓存值再包一层
 * WeakReference，避免 Class/Method 反向强引用 ClassLoader 导致弱键永远
 * 不可回收（否则 Tinker 每次热修的新 loader 都会永久驻留）。
 */
public final class MountResolver {

    private static final String TAG = "CloudMusicTool";
    private static final String TARGET_PREFIX = "com.netease.";

    /** null ClassLoader（bootstrap loader）的固定键；ClassLoader 不可能与它相等。 */
    private static final Object BOOTSTRAP_LOADER_KEY = new Object();

    private static final Map<Object, Map<String, WeakReference<Class<?>>>> CLASS_CACHE =
            new WeakHashMap<>();
    private static final Map<Object, Map<String, WeakReference<Method>>> METHOD_CACHE =
            new WeakHashMap<>();

    private MountResolver() {
    }

    private static Object loaderKey(ClassLoader cl) {
        return cl == null ? BOOTSTRAP_LOADER_KEY : cl;
    }

    private static <T> T cacheGet(Map<Object, Map<String, WeakReference<T>>> cache,
                                  Object key, String specId) {
        synchronized (cache) {
            Map<String, WeakReference<T>> perLoader = cache.get(key);
            if (perLoader == null) {
                return null;
            }
            WeakReference<T> ref = perLoader.get(specId);
            if (ref == null) {
                return null;
            }
            T value = ref.get();
            if (value == null) {
                // 目标 Class/Method 已被卸载，清掉失效条目。
                perLoader.remove(specId);
            }
            return value;
        }
    }

    private static <T> void cachePut(Map<Object, Map<String, WeakReference<T>>> cache,
                                     Object key, String specId, T value) {
        synchronized (cache) {
            Map<String, WeakReference<T>> perLoader = cache.get(key);
            if (perLoader == null) {
                perLoader = new HashMap<>();
                cache.put(key, perLoader);
            }
            perLoader.put(specId, new WeakReference<>(value));
        }
    }

    public static Class<?> resolveClass(MountSpec spec, ClassLoader cl) {
        if (spec == null || cl == null) {
            return null;
        }
        Object key = loaderKey(cl);
        Class<?> cached = cacheGet(CLASS_CACHE, key, spec.id);
        if (cached != null) {
            return cached;
        }
        Class<?> found = null;
        if (spec.classCandidates != null) {
            for (String candidate : spec.classCandidates) {
                if (candidate == null || candidate.isEmpty()) {
                    continue;
                }
                try {
                    Class<?> cls = Class.forName(candidate, false, cl);
                    if (!isTargetClass(cls, cl)) {
                        continue;
                    }
                    if (spec.methodName != null && !hasMethodNamed(cls, spec.methodName)) {
                        continue;
                    }
                    found = cls;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (found == null && spec.anchors != null && spec.anchors.length > 0) {
            try {
                Class<?> fallback = DexKitLocator.findClass(
                        cl, spec.primaryClass(), spec.anchors);
                if (fallback != null && isTargetClass(fallback, cl)
                        && (spec.methodName == null || hasMethodNamed(fallback, spec.methodName))) {
                    found = fallback;
                    Log.i(TAG, "profile fallback class " + spec.id + " -> " + fallback.getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "profile fallback failed " + spec.id + ": " + t.getMessage());
            }
        }
        if (found != null) {
            cachePut(CLASS_CACHE, key, spec.id, found);
        } else {
            Log.w(TAG, "profile class unresolved " + spec.id);
        }
        return found;
    }

    public static Method resolveMethod(MountSpec spec, Class<?> cls, ClassLoader cl) {
        if (spec == null || cls == null || spec.methodName == null) {
            return null;
        }
        // 方法归属由声明类决定：Class 的 ClassLoader 是缓存隔离的第一依据，
        // 传入的 cl 只在声明类由 bootstrap loader 加载时作为兜底。
        // 同一 ClassLoader 下不同 Class 可能有同名同签名方法（如两个 Block 子类），
        // 缓存键必须带类名，否则后一个 Class 会命中前一个 Class 的 Method。
        Object key = cls.getClassLoader() != null
                ? cls.getClassLoader() : loaderKey(cl);
        String cacheKey = spec.id + "@" + cls.getName();
        Method cached = cacheGet(METHOD_CACHE, key, cacheKey);
        if (cached != null) {
            return cached;
        }
        Method found = null;
        Class<?>[] params = TypeResolver.resolveAll(spec.paramTypes, cl);
        if (spec.paramTypes != null && params == null) {
            Log.w(TAG, "profile param types unresolved " + spec.id);
            return null;
        }
        if (params != null) {
            found = findMethodInHierarchy(cls, spec.methodName, params);
        } else {
            Class<?> returnType = TypeResolver.resolve(spec.returnType, cl);
            Method unique = null;
            int count = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (!spec.methodName.equals(m.getName())) {
                    continue;
                }
                if (returnType != null && !returnType.equals(m.getReturnType())) {
                    continue;
                }
                unique = m;
                count++;
            }
            if (count == 1) {
                found = unique;
            } else if (count > 1) {
                Log.w(TAG, "profile method ambiguous " + spec.id + " candidates=" + count);
            }
        }
        if (found != null) {
            if (spec.returnType != null) {
                Class<?> expected = TypeResolver.resolve(spec.returnType, cl);
                if (expected != null && !expected.equals(found.getReturnType())) {
                    Log.w(TAG, "profile return mismatch " + spec.id
                            + " expected=" + spec.returnType
                            + " actual=" + found.getReturnType().getName());
                    return null;
                }
            }
            cachePut(METHOD_CACHE, key, cacheKey, found);
        } else {
            Log.w(TAG, "profile method unresolved " + spec.id);
        }
        return found;
    }

    private static Method findMethodInHierarchy(Class<?> cls, String name, Class<?>[] params) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean hasMethodNamed(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName())) {
                    return true;
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean isTargetClass(Class<?> cls, ClassLoader cl) {
        String name = cls.getName();
        if (name == null || name.startsWith("com.cloudmusictool.")) {
            return false;
        }
        if (name.startsWith(TARGET_PREFIX)) {
            return true;
        }
        // 混淆后的顶层类（q60.w0、fw0.e 等）不在 com.netease.* 下，
        // 以“由宿主 ClassLoader 加载”作为归属校验，拒绝模块自身类。
        return cls.getClassLoader() == cl;
    }
}
