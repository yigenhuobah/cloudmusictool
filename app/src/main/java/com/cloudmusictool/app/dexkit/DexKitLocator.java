package com.cloudmusictool.app.dexkit;

import android.os.Build;
import android.util.Log;

import com.cloudmusictool.app.TargetProcessPublisher;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DexKit 动态扫描定位引擎。
 * 懒加载 DexKitBridge 并按 ClassLoader 缓存；当硬编码定位失败时作为兜底机制介入。
 * 严格禁止引用任何 androidx 类型。
 *
 * native 库加载顺序（三者任一成功即可）：
 *   1. 模块 APK 的 nativeLibraryDir/libdexkit.so
 *   2. System.loadLibrary("dexkit")
 *   3. 从模块 APK（getModuleApplicationInfo().sourceDir）解压 lib/<abi>/libdexkit.so
 *      到目标进程 cacheDir 后 System.load
 */
public final class DexKitLocator {

    private static final String TAG = "CloudMusicTool";
    private static final long SLOW_QUERY_MS = 300L;

    private static final Map<ClassLoader, DexKitBridge> sBridgeCache = new WeakHashMap<>();
    private static final Map<ClassLoader, Map<String, Class<?>>> sClassCache = new WeakHashMap<>();
    private static final Map<String, Boolean> sWarmUpKeys = new ConcurrentHashMap<>();

    private static volatile String sModuleNativeDir;
    private static volatile String sModuleApkPath;
    private static volatile boolean sNativeLoaded;
    private static volatile String sLoadFailure = "not attempted";

    private DexKitLocator() {
    }

    public static void setModuleNativeDir(String dir) {
        sModuleNativeDir = dir;
    }

    public static void setModuleApkPath(String path) {
        sModuleApkPath = path;
    }

    public static synchronized DexKitBridge getBridge(ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        DexKitBridge bridge = sBridgeCache.get(cl);
        if (bridge != null) {
            return bridge;
        }
        if (!ensureNativeLoaded()) {
            Log.w(TAG, "DexKit disabled: " + sLoadFailure);
            return null;
        }
        try {
            long start = System.currentTimeMillis();
            bridge = DexKitBridge.create(cl, false);
            sBridgeCache.put(cl, bridge);
            Log.i(TAG, "DexKitBridge initialized successfully for ClassLoader: " + cl
                    + " (" + (System.currentTimeMillis() - start) + "ms)");
        } catch (Throwable t) {
            sLoadFailure = "DexKitBridge.create failed: " + t.getMessage();
            Log.w(TAG, "DexKit disabled: " + sLoadFailure, t);
        }
        return bridge;
    }

    private static synchronized boolean ensureNativeLoaded() {
        if (sNativeLoaded) {
            return true;
        }
        // 1. 模块 nativeLibraryDir（useLegacyPackaging=true 时存在）
        String dir = sModuleNativeDir;
        if (dir != null && !dir.isEmpty()) {
            File so = new File(dir, "libdexkit.so");
            if (so.isFile() && tryLoad(so.getAbsolutePath(), "moduleNativeDir")) {
                sNativeLoaded = true;
                return true;
            }
        }
        // 2. 目标进程的 library path（通常只有宿主自己的 so）
        if (tryLoadLibrary()) {
            sNativeLoaded = true;
            return true;
        }
        // 3. 从模块 APK 解压到目标进程 cacheDir
        if (extractFromModuleApk()) {
            sNativeLoaded = true;
            return true;
        }
        if (sLoadFailure == null) {
            sLoadFailure = "libdexkit.so not found (nativeDir=" + dir
                    + ", apk=" + sModuleApkPath + ")";
        }
        return false;
    }

    private static boolean tryLoad(String path, String source) {
        try {
            System.load(path);
            Log.i(TAG, "DexKit native loaded from " + source + ": " + path);
            return true;
        } catch (Throwable t) {
            sLoadFailure = source + " load failed: " + t.getMessage();
            Log.w(TAG, "DexKit native load from " + source + " failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean tryLoadLibrary() {
        try {
            System.loadLibrary("dexkit");
            Log.i(TAG, "DexKit native loaded via System.loadLibrary(\"dexkit\")");
            return true;
        } catch (Throwable t) {
            sLoadFailure = "loadLibrary failed: " + t.getMessage();
            Log.w(TAG, "DexKit loadLibrary failed: " + t.getMessage());
            return false;
        }
    }

    /** 从模块 APK 解压 lib/<abi>/libdexkit.so 到目标进程 cacheDir。 */
    private static boolean extractFromModuleApk() {
        String apkPath = sModuleApkPath;
        if (apkPath == null || apkPath.isEmpty()) {
            return false;
        }
        File apk = new File(apkPath);
        if (!apk.isFile()) {
            sLoadFailure = "module apk missing: " + apkPath;
            return false;
        }
        // 目标 Context 经共享缓存助手解析（原为无缓存直反射，现成功一次后缓存）。
        android.content.Context context = TargetProcessPublisher.targetContext();
        if (context == null) {
            sLoadFailure = "target context unavailable for so extraction";
            return false;
        }
        for (String abi : supportedAbis()) {
            ZipFile zip = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                zip = new ZipFile(apk);
                ZipEntry entry = zip.getEntry("lib/" + abi + "/libdexkit.so");
                if (entry == null) {
                    continue;
                }
                File soDir = new File(context.getCacheDir(), "dexkit");
                if (!soDir.isDirectory() && !soDir.mkdirs() && !soDir.isDirectory()) {
                    sLoadFailure = "cannot create cache dir " + soDir;
                    return false;
                }
                File so = new File(soDir, "libdexkit-" + abi + ".so");
                in = zip.getInputStream(entry);
                out = new FileOutputStream(so);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                out = null;
                if (tryLoad(so.getAbsolutePath(), "apkExtract/" + abi)) {
                    return true;
                }
            } catch (Throwable t) {
                sLoadFailure = "extract " + abi + " failed: " + t.getMessage();
                Log.w(TAG, "DexKit extract " + abi + " failed: " + t.getMessage());
            } finally {
                closeQuietly(in);
                closeQuietly(out);
                closeQuietly(zip);
            }
        }
        return false;
    }

    private static String[] supportedAbis() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) {
            return new String[]{"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};
        }
        return abis;
    }

    private static void closeQuietly(Object closeable) {
        try {
            if (closeable instanceof java.io.Closeable) {
                ((java.io.Closeable) closeable).close();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 兜底查找类：
     * 有 anchors 时按 anchors 查询；命中后只保留 com.netease.cloudmusic 包内的候选，
     * 优先与硬编码类简名完全一致的候选；多候选且无法消歧时 WARN 并返回 null。
     */
    public static Class<?> findClass(ClassLoader cl, String className, String... anchors) {
        if (anchors == null || anchors.length == 0) {
            return null;
        }
        String key = anchorKey(anchors);
        if (key == null) {
            return null;
        }
        Map<String, Class<?>> cache = classCache(cl);
        if (cache != null) {
            Class<?> cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        DexKitBridge bridge = getBridge(cl);
        if (bridge == null) {
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            FindClass findClass = FindClass.create();
            ClassMatcher matcher = ClassMatcher.create();
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher.addUsingString(anchor);
                }
            }
            findClass.matcher(matcher);
            ClassDataList list = bridge.findClass(findClass);
            long elapsed = System.currentTimeMillis() - start;
            if (list == null || list.isEmpty()) {
                Log.w(TAG, "DexKit findClass: 0 candidates found for targetHint=" + className
                        + " (elapsed=" + elapsed + "ms)");
                return null;
            }

            String wantedSimple = simpleName(className);
            List<Class<?>> targetCandidates = new ArrayList<>();
            List<Class<?>> exactCandidates = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Class<?> c;
                try {
                    c = list.get(i).getInstance(cl);
                } catch (Throwable t) {
                    continue;
                }
                if (c == null || !isTargetClass(c, cl)) {
                    continue;
                }
                targetCandidates.add(c);
                if (wantedSimple != null && wantedSimple.equals(c.getSimpleName())) {
                    exactCandidates.add(c);
                }
            }

            Class<?> chosen;
            if (exactCandidates.size() == 1) {
                chosen = exactCandidates.get(0);
            } else if (exactCandidates.size() > 1) {
                Log.w(TAG, "DexKit findClass ambiguous: " + exactCandidates.size()
                        + " candidates match simple name '" + wantedSimple + "' for " + className
                        + "; refusing to guess");
                return null;
            } else if (targetCandidates.size() == 1) {
                chosen = targetCandidates.get(0);
            } else if (targetCandidates.size() > 1) {
                Log.w(TAG, "DexKit findClass ambiguous: " + targetCandidates.size()
                        + " target-package candidates for " + className + "; refusing to guess");
                return null;
            } else {
                Log.w(TAG, "DexKit findClass: no candidate in target package/classloader for " + className);
                return null;
            }

            if (cache != null) {
                cache.put(key, chosen);
            }
            Log.i(TAG, "DexKit fallback found class (candidates=" + targetCandidates.size()
                    + ", chosen=" + chosen.getName() + ", targetHint=" + className
                    + ", elapsed=" + elapsed + "ms)");
            if (elapsed > SLOW_QUERY_MS) {
                Log.w(TAG, "DexKit slow findClass " + elapsed + "ms; result cached for later queries");
            }
            return chosen;
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findClass error for " + className + ": " + t.getMessage());
        }
        return null;
    }

    /**
     * 候选类归属校验，与 MountResolver 保持一致：
     * - com.netease.* 直接接受；
     * - 混淆后的顶层类（q60.w0、dr.h、fw0.e 等）不在 com.netease 下，
     *   以“由宿主 ClassLoader 加载”作为归属依据；
     * - 模块自身类（com.cloudmusictool.*）一律排除。
     */
    private static boolean isTargetClass(Class<?> cls, ClassLoader cl) {
        if (cls == null) {
            return false;
        }
        String name = cls.getName();
        if (name == null || name.startsWith("com.cloudmusictool.")) {
            return false;
        }
        if (name.startsWith("com.netease.")) {
            return true;
        }
        return cl != null && cls.getClassLoader() == cl;
    }

    private static String simpleName(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        String name = className.substring(className.lastIndexOf('.') + 1);
        int dollar = name.lastIndexOf('$');
        return dollar >= 0 ? name.substring(dollar + 1) : name;
    }

    /**
     * 兜底查找方法：
     * 分两次尝试：
     *  第一次：declaredClass(actualClassName) + name(methodName) + anchors；
     *  第二次（第一次无结果或未通过校验时）：只按 anchors 查询，不限制类名/方法名/返回类型/参数类型。
     *  命中后如果调用方提供了 returnType，做一次返回类型 sanity check（不一致就丢弃并记录日志）；参数类型不做强约束。
     */
    public static Method findMethod(ClassLoader cl, String actualClassName, String methodName,
                                    Class<?> returnType, Class<?>[] paramTypes, String... anchors) {
        if (anchors == null || anchors.length == 0) {
            return null;
        }
        boolean hasValidAnchor = false;
        for (String a : anchors) {
            if (a != null && !a.isEmpty()) {
                hasValidAnchor = true;
                break;
            }
        }
        if (!hasValidAnchor) {
            return null;
        }

        DexKitBridge bridge = getBridge(cl);
        if (bridge == null) {
            return null;
        }
        long start = System.currentTimeMillis();

        // 第一次尝试：declaredClass(actualClassName) + name(methodName) + anchors
        try {
            FindMethod query1 = FindMethod.create();
            MethodMatcher matcher1 = MethodMatcher.create();
            if (actualClassName != null && !actualClassName.isEmpty()) {
                matcher1.declaredClass(actualClassName);
            }
            if (methodName != null && !methodName.isEmpty()) {
                matcher1.name(methodName);
            }
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher1.addUsingString(anchor);
                }
            }
            query1.matcher(matcher1);
            MethodDataList list1 = bridge.findMethod(query1);
            if (list1 != null && !list1.isEmpty()) {
                Method m = selectMatchingMethod(cl, list1, returnType, paramTypes, "attempt1",
                        actualClassName, methodName);
                if (m != null) {
                    logQueryTime("findMethod attempt1", start);
                    return m;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findMethod attempt 1 error: " + t.getMessage());
        }

        // 第二次尝试（第一次无结果或未通过校验时）：仍按 anchors 查询，
        // 但结果必须通过类名/方法名/参数/返回类型校验，不能直接取第一个候选。
        try {
            FindMethod query2 = FindMethod.create();
            MethodMatcher matcher2 = MethodMatcher.create();
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher2.addUsingString(anchor);
                }
            }
            query2.matcher(matcher2);
            MethodDataList list2 = bridge.findMethod(query2);
            if (list2 != null && !list2.isEmpty()) {
                Method m = selectMatchingMethod(cl, list2, returnType, paramTypes, "attempt2",
                        actualClassName, methodName);
                if (m != null) {
                    logQueryTime("findMethod attempt2", start);
                    return m;
                }
            } else {
                Log.w(TAG, "DexKit findMethod attempt 2: 0 candidates found for anchors on "
                        + actualClassName + "#" + methodName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "DexKit findMethod attempt 2 error: " + t.getMessage());
        }
        logQueryTime("findMethod (miss)", start);
        return null;
    }

    private static void logQueryTime(String what, long start) {
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > SLOW_QUERY_MS) {
            Log.w(TAG, "DexKit slow " + what + " " + elapsed + "ms (main thread risk)");
        } else {
            Log.i(TAG, "DexKit " + what + " elapsed=" + elapsed + "ms");
        }
    }

    /**
     * 异步预热：避免首次兜底查询落在主线程导致 ANR。
     * 同一 ClassLoader + anchor 组合只执行一次。
     */
    public static void warmUpAsync(final ClassLoader cl, final String className, final String... anchors) {
        if (cl == null || anchors == null || anchors.length == 0) {
            return;
        }
        final String key = anchorKey(anchors);
        if (key == null) {
            return;
        }
        if (sWarmUpKeys.putIfAbsent(cl.hashCode() + ":" + key, Boolean.TRUE) != null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                findClass(cl, className, anchors);
            } catch (Throwable ignored) {
            }
        }, "CloudMusicTool-DexKitWarmup");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, Class<?>> classCache(ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        synchronized (sClassCache) {
            Map<String, Class<?>> map = sClassCache.get(cl);
            if (map == null) {
                map = new ConcurrentHashMap<>();
                sClassCache.put(cl, map);
            }
            return map;
        }
    }

    private static String anchorKey(String... anchors) {
        StringBuilder sb = new StringBuilder();
        for (String a : anchors) {
            if (a != null && !a.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\u0001');
                }
                sb.append(a);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Method selectMatchingMethod(ClassLoader cl, MethodDataList list, Class<?> returnType,
                                              Class<?>[] paramTypes, String attemptLabel,
                                              String targetClass, String targetMethod) {
        List<Method> valid = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            MethodData data = list.get(i);
            try {
                Method m = data.getMethodInstance(cl);
                if (targetClass != null && !targetClass.isEmpty()
                        && !targetClass.equals(m.getDeclaringClass().getName())) {
                    continue;
                }
                if (targetMethod != null && !targetMethod.isEmpty()
                        && !targetMethod.equals(m.getName())) {
                    continue;
                }
                if (paramTypes != null && !paramsMatch(m.getParameterTypes(), paramTypes)) {
                    continue;
                }
                if (returnType != null && !isReturnTypeCompatible(returnType, m.getReturnType())) {
                    Log.w(TAG, "DexKit " + attemptLabel + " candidate " + i + " ("
                            + m.getDeclaringClass().getName() + "#" + m.getName()
                            + ") returnType mismatch: expected " + returnType.getName()
                            + ", got " + m.getReturnType().getName() + "; discarded");
                    continue;
                }
                valid.add(m);
            } catch (Throwable t) {
                Log.w(TAG, "DexKit " + attemptLabel + " getMethodInstance error: " + t.getMessage());
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        if (returnType == null && valid.size() > 1) {
            Log.w(TAG, "DexKit " + attemptLabel + " ambiguous: " + valid.size()
                    + " candidates for " + targetClass + "#" + targetMethod
                    + " and no returnType to disambiguate; refusing to guess");
            return null;
        }
        Method chosen = valid.get(0);
        Log.i(TAG, "DexKit " + attemptLabel + " chosen method (valid=" + valid.size() + ", chosen="
                + chosen.getDeclaringClass().getName() + "#" + chosen.getName()
                + ", targetHint=" + targetClass + "#" + targetMethod + ")");
        return chosen;
    }

    private static boolean paramsMatch(Class<?>[] actual, Class<?>[] expected) {
        if (actual == null || expected == null) {
            return true;
        }
        if (actual.length != expected.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (expected[i] != null && actual[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isReturnTypeCompatible(Class<?> expected, Class<?> actual) {
        if (expected == null || actual == null) {
            return true;
        }
        if (expected == actual || expected.equals(actual) || expected.isAssignableFrom(actual)) {
            return true;
        }
        if (expected == boolean.class && actual == Boolean.class) return true;
        if (expected == Boolean.class && actual == boolean.class) return true;
        if (expected == int.class && actual == Integer.class) return true;
        if (expected == Integer.class && actual == int.class) return true;
        if (expected == long.class && actual == Long.class) return true;
        if (expected == Long.class && actual == long.class) return true;
        if (expected == void.class && actual == Void.class) return true;
        return false;
    }
}
