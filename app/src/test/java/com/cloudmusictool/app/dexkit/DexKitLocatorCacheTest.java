package com.cloudmusictool.app.dexkit;

import com.cloudmusictool.app.TargetProcessPublisher;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * DexKitLocator 兜底定位的缓存与失败语义回归（深度复核 id 7/8/9/36/37）：
 *
 * 1. id 36 —— findClass 缓存键必须带 className：同一 anchor 集合被不同 hint 复用时
 *    不得互相命中；命中后按写入时的简名判定复核，脏 entry 移除而不是静默返回。
 * 2. id 7 —— 扫描失败负缓存：miss 命中时在触碰 native 之前短路（不再重复全量扫描）；
 *    findMethod 的失败键必须覆盖类/方法/返回类型/参数签名/anchor 集合；
 *    bridge 不可用（native 未就绪）时不得写入负缓存污染后续查询。
 * 3. id 9 —— native 加载链整体失败后本进程内短路（不再重复 loadLibrary/解压）；
 *    仅 Context 未就绪时按可恢复问题延迟（不写死失败标记）。
 * 4. id 37 —— warmUpAsync 按 ClassLoader 身份 + className + anchor 集合去重，
 *    不再依赖 identity hash。
 * 5. id 8 —— findMethod 的入参守卫（空/全空 anchor、null loader 直接拒绝）。
 *    attempt1 抛异常才允许 attempt2 的门禁本身需要真实 DexKitBridge（native），
 *    JVM 单测无法执行，属于本测试套件的已知覆盖边界。
 *
 * 说明：键格式（"c\u0002"/"m\u0002"、\u0001、\u0002 分隔）是生产实现的白盒契约，
 * 在此固定以防键身份漂移；若实现调整键格式，需同步更新本测试。
 */
public class DexKitLocatorCacheTest {

    /** 简名与夹具类一致的 hint：isTargetClass 按 loader 归属放行。 */
    private static final String HIT_HINT = "com.netease.cloudmusic.JSONObject";
    private static final String MISS_HINT = "com.netease.cloudmusic.MismatchHint";

    private URLClassLoader hostLoader;
    /** 由 hostLoader 定义的 org.json.JSONObject 副本：getClassLoader() == hostLoader。 */
    private Class<?> hostJsonObject;

    @Before
    public void resetLocatorState() throws Exception {
        hostLoader = newHostLoader();
        hostJsonObject = hostLoader.loadClass("org.json.JSONObject");
        resetStatics();
    }

    @After
    public void cleanUpLocatorState() throws Exception {
        resetStatics();
        hostLoader = null;
    }

    // ---------------------------------------------------------------- id 36

    @Test
    public void findClass_cacheHitReturnsCachedInstanceWithoutTouchingNative() throws Exception {
        seedClassCache(hostLoader, classKey(HIT_HINT, "anchor-a"),
                newCachedEntry(hostJsonObject, true));

        Class<?> found = DexKitLocator.findClass(hostLoader, HIT_HINT, "anchor-a");
        Class<?> foundAgain = DexKitLocator.findClass(hostLoader, HIT_HINT, "anchor-a");

        assertEquals("org.json.JSONObject", found.getName());
        assertSame("返回的必须是 hostLoader 定义的副本", hostLoader, found.getClassLoader());
        assertSame("两次命中必须返回同一缓存实例", found, foundAgain);
        assertEquals("命中路径不得触碰 native 加载链", "not attempted", loadFailure());
        assertFalse(loadFailed());
    }

    @Test
    public void findClass_cacheKeySeparatesDifferentClassNameHints() throws Exception {
        String keyA = classKey(HIT_HINT, "anchor-a");
        seedClassCache(hostLoader, keyA, newCachedEntry(hostJsonObject, true));

        // 同一 anchor 集合、不同 className hint：不得命中别的 hint 的 entry。
        Class<?> found = DexKitLocator.findClass(hostLoader, MISS_HINT, "anchor-a");

        assertNull("不同 className hint 必须各自成键", found);
        assertNotNull("原 hint 的 entry 不得被误删",
                classCacheFor(hostLoader).get(keyA));
        assertTrue("未命中应继续走到 bridge（native 尝试可观测）",
                loadFailure().startsWith("loadLibrary failed"));
    }

    @Test
    public void findClass_staleSimpleNameFlagEvictsEntryInsteadOfReturning() throws Exception {
        String key = classKey(MISS_HINT, "anchor-a");
        // 写入标记 simpleNameMatched=true，但 hint 简名与实际类不一致：脏 entry。
        seedClassCache(hostLoader, key, newCachedEntry(hostJsonObject, true));

        Class<?> found = DexKitLocator.findClass(hostLoader, MISS_HINT, "anchor-a");

        assertNull("简名复核不一致的脏 entry 不得返回", found);
        assertNull("脏 entry 必须被移除", classCacheFor(hostLoader).get(key));
    }

    @Test
    public void findClass_consistentUnmatchedSimpleNameFlagStillHits() throws Exception {
        String key = classKey(MISS_HINT, "anchor-a");
        // 写入时简名未命中 hint（false）且当前复核同样不命中：判定一致，允许返回。
        seedClassCache(hostLoader, key, newCachedEntry(hostJsonObject, false));

        Class<?> found = DexKitLocator.findClass(hostLoader, MISS_HINT, "anchor-a");

        assertSame("写入时判定与复核一致即可命中", hostJsonObject, found);
    }

    // ---------------------------------------------------------------- id 7

    @Test
    public void findClass_missCacheShortCircuitsBeforeNativeLoad() throws Exception {
        String key = classKey(HIT_HINT, "anchor-a");
        seedMissCache(hostLoader, key);

        assertNull("负缓存命中必须返回 null", DexKitLocator.findClass(hostLoader, HIT_HINT, "anchor-a"));
        assertEquals("负缓存短路必须发生在 native 加载之前", "not attempted", loadFailure());
        assertFalse(loadFailed());

        // 对照组：清掉负缓存后同一条查询会真的尝试 native（保证上面的断言不是恒真）。
        missCacheFor(hostLoader).remove(key);
        assertNull(DexKitLocator.findClass(hostLoader, HIT_HINT, "anchor-a"));
        assertTrue("负缓存外的查询应走到 native 加载尝试",
                loadFailure().startsWith("loadLibrary failed"));
    }

    @Test
    public void findMethod_missCacheShortCircuitsAndKeyCoversSignature() throws Exception {
        String key = methodKey("com.netease.cloudmusic.ApmProbe", "logApm", null, null, "anchor-a");
        seedMissCache(hostLoader, key);

        assertNull("负缓存命中必须返回 null",
                DexKitLocator.findMethod(hostLoader, "com.netease.cloudmusic.ApmProbe",
                        "logApm", null, null, "anchor-a"));
        assertEquals("负缓存短路必须发生在 native 加载之前", "not attempted", loadFailure());

        // 参数签名参与键身份：换签名后不得命中旧负缓存。
        assertNull(DexKitLocator.findMethod(hostLoader, "com.netease.cloudmusic.ApmProbe",
                "logApm", null, new Class<?>[]{String.class}, "anchor-a"));
        assertTrue("不同签名必须绕开旧负缓存继续查询",
                loadFailure().startsWith("loadLibrary failed"));
    }

    @Test
    public void queriesWithoutBridgeDoNotPoisonMissCache() throws Exception {
        assertNull(DexKitLocator.findClass(hostLoader, "com.netease.cloudmusic.Whatever", "anchor-a"));
        assertNull(DexKitLocator.findMethod(hostLoader, "com.netease.cloudmusic.Whatever",
                "whatever", null, null, "anchor-a"));

        Map<String, Boolean> miss = missCacheFor(hostLoader);
        assertTrue("bridge 不可用时不得写负缓存（native 未就绪不能被写死）", miss.isEmpty());
    }

    // ---------------------------------------------------------------- id 9

    @Test
    public void getBridge_nativeLoadFailureIsPermanentForProcess() throws Exception {
        // sModuleNativeDir/sModuleApkPath 保持 null：加载链只能走 System.loadLibrary 且必失败。
        assertNull(getBridge(hostLoader));
        assertTrue("整条加载链失败后必须写死失败标记", loadFailed());
        assertTrue(loadFailure().startsWith("loadLibrary failed"));

        String failureAfterFirst = loadFailure();
        assertNull(getBridge(hostLoader));
        assertEquals("失败标记短路：第二次查询不得重复尝试加载", failureAfterFirst, loadFailure());
        assertTrue(loadFailed());
    }

    @Test
    public void getBridge_defersWhenTargetContextUnavailable() throws Exception {
        File nativeDir = Files.createTempDirectory("dexkit-test-nodir").toFile();
        File fakeApk = File.createTempFile("dexkit-test", ".apk");
        try {
            setStatic("sModuleNativeDir", nativeDir.getAbsolutePath());
            setStatic("sModuleApkPath", fakeApk.getAbsolutePath());

            assertNull(getBridge(hostLoader));
            assertFalse("Context 未就绪属可恢复环境问题，不得写死失败标记", loadFailed());
            assertEquals("失败原因应停在解压前置条件上",
                    "target context unavailable for so extraction", loadFailure());

            assertNull(getBridge(hostLoader));
            assertFalse("延迟分支允许后续重试", loadFailed());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            fakeApk.delete();
            //noinspection ResultOfMethodCallIgnored
            nativeDir.delete();
        }
    }

    // ---------------------------------------------------------------- id 37

    @Test
    public void warmUpAsync_dedupesPerLoaderClassNameAndAnchors() throws Exception {
        // 背景线程会调 findClass：先按 key 种好负缓存让它短路，避免触碰 native 状态。
        seedMissCache(hostLoader, classKey(HIT_HINT, "anchor-a"));
        seedMissCache(hostLoader, classKey("com.netease.cloudmusic.Other", "anchor-a"));
        seedMissCache(hostLoader, classKey(HIT_HINT, "anchor-b"));

        DexKitLocator.warmUpAsync(hostLoader, HIT_HINT, "anchor-a");
        assertEquals(new HashSet<>(java.util.Collections.singletonList(
                HIT_HINT + "\u0002anchor-a")), warmUpKeysFor(hostLoader));

        DexKitLocator.warmUpAsync(hostLoader, HIT_HINT, "anchor-a");
        assertEquals("同 loader + 同 className + 同 anchor 只预热一次", 1, warmUpKeysFor(hostLoader).size());

        DexKitLocator.warmUpAsync(hostLoader, "com.netease.cloudmusic.Other", "anchor-a");
        DexKitLocator.warmUpAsync(hostLoader, HIT_HINT, "anchor-b");
        assertEquals("不同 className hint / anchor 集合需要各自预热", 3, warmUpKeysFor(hostLoader).size());

        URLClassLoader otherLoader = newHostLoader();
        try {
            seedMissCache(otherLoader, classKey(HIT_HINT, "anchor-a"));
            DexKitLocator.warmUpAsync(otherLoader, HIT_HINT, "anchor-a");
            assertEquals("预热键必须按 ClassLoader 身份隔离", 1, warmUpKeysFor(otherLoader).size());
            assertEquals(3, warmUpKeysFor(hostLoader).size());
        } finally {
            otherLoader.close();
        }
    }

    @Test
    public void warmUpAsync_ignoresNullLoaderAndBlankAnchors() throws Exception {
        DexKitLocator.warmUpAsync(null, HIT_HINT, "anchor-a");
        DexKitLocator.warmUpAsync(hostLoader, HIT_HINT);
        DexKitLocator.warmUpAsync(hostLoader, HIT_HINT, "", null);

        assertTrue("无效入参不得产生预热键", warmUpKeysFor(hostLoader).isEmpty());
        assertTrue(warmUpKeysMap().isEmpty());
    }

    // ---------------------------------------------------------------- id 8（守卫部分）

    @Test
    public void lookupGuards_rejectNullLoaderAndBlankAnchors() throws Exception {
        assertNull(DexKitLocator.findClass(null, HIT_HINT, "anchor-a"));
        assertNull("无 anchors 的 findClass 直接拒绝",
                DexKitLocator.findClass(hostLoader, HIT_HINT));
        assertNull("全空白 anchors 的 findClass 直接拒绝",
                DexKitLocator.findClass(hostLoader, HIT_HINT, "", null));
        assertNull(DexKitLocator.findMethod(null, "a.A", "b", null, null, "anchor-a"));
        assertNull("无 anchors 的 findMethod 直接拒绝",
                DexKitLocator.findMethod(hostLoader, "a.A", "b", null, null));
        assertNull("全空白 anchors 的 findMethod 直接拒绝",
                DexKitLocator.findMethod(hostLoader, "a.A", "b", null, null, "", null));

        assertNull("守卫路径不得创建任何缓存结构", classCacheMap().get(hostLoader));
        assertNull(missCacheMap().get(hostLoader));
    }

    // ---------------------------------------------------------------- 夹具与反射工具

    /** 用独立 ClassLoader 加载 org.json.JSONObject：非 com.netease 类按 loader 归属放行。 */
    private static URLClassLoader newHostLoader() throws Exception {
        URL jarUrl = JSONObject.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull("org.json 必须在测试 classpath 上", jarUrl);
        return new URLClassLoader(new URL[]{jarUrl}, null);
    }

    private static void resetStatics() throws Exception {
        // 四个缓存容器是 static final：只能清空内容，不能整体替换。
        //noinspection rawtypes
        ((Map) staticField("sBridgeCache").get(null)).clear();
        //noinspection rawtypes
        ((Map) staticField("sClassCache").get(null)).clear();
        //noinspection rawtypes
        ((Map) staticField("sMissCache").get(null)).clear();
        //noinspection rawtypes
        ((Map) staticField("sWarmUpKeys").get(null)).clear();
        setStatic("sModuleNativeDir", null);
        setStatic("sModuleApkPath", null);
        setStatic("sNativeLoaded", false);
        setStatic("sNativeLoadFailed", false);
        setStatic("sLoadFailure", "not attempted");
        // 保证 targetContext() 在单测里走未就绪分支（反射缓存清零）。
        Field ctx = TargetProcessPublisher.class.getDeclaredField("sTargetContext");
        ctx.setAccessible(true);
        ctx.set(null, null);
    }

    private static Object newCachedEntry(Class<?> cls, boolean simpleNameMatched) throws Exception {
        Class<?> cc = Class.forName("com.cloudmusictool.app.dexkit.DexKitLocator$CachedClass");
        Constructor<?> ctor = cc.getDeclaredConstructor(Class.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(cls, simpleNameMatched);
    }

    @SuppressWarnings("unchecked")
    private static Map<ClassLoader, Map<String, Object>> classCacheMap() throws Exception {
        return (Map<ClassLoader, Map<String, Object>>) staticField("sClassCache").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<ClassLoader, Map<String, Boolean>> missCacheMap() throws Exception {
        return (Map<ClassLoader, Map<String, Boolean>>) staticField("sMissCache").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<ClassLoader, Set<String>> warmUpKeysMap() throws Exception {
        return (Map<ClassLoader, Set<String>>) staticField("sWarmUpKeys").get(null);
    }

    private static Map<String, Object> classCacheFor(ClassLoader cl) throws Exception {
        Map<String, Object> inner = classCacheMap().get(cl);
        return inner == null ? new HashMap<String, Object>() : inner;
    }

    private static Map<String, Boolean> missCacheFor(ClassLoader cl) throws Exception {
        Map<String, Boolean> inner = missCacheMap().get(cl);
        return inner == null ? new HashMap<String, Boolean>() : inner;
    }

    private static Set<String> warmUpKeysFor(ClassLoader cl) throws Exception {
        Set<String> keys = warmUpKeysMap().get(cl);
        return keys == null ? new java.util.HashSet<String>() : keys;
    }

    private static void seedClassCache(ClassLoader cl, String key, Object entry) throws Exception {
        Map<ClassLoader, Map<String, Object>> outer = classCacheMap();
        Map<String, Object> inner = outer.get(cl);
        if (inner == null) {
            inner = new HashMap<>();
            outer.put(cl, inner);
        }
        inner.put(key, entry);
    }

    private static void seedMissCache(ClassLoader cl, String key) throws Exception {
        Map<ClassLoader, Map<String, Boolean>> outer = missCacheMap();
        Map<String, Boolean> inner = outer.get(cl);
        if (inner == null) {
            inner = new HashMap<>();
            outer.put(cl, inner);
        }
        inner.put(key, Boolean.TRUE);
    }

    private static Object getBridge(ClassLoader cl) {
        return DexKitLocator.getBridge(cl);
    }

    private static String loadFailure() throws Exception {
        return (String) staticField("sLoadFailure").get(null);
    }

    private static boolean loadFailed() throws Exception {
        return (Boolean) staticField("sNativeLoadFailed").get(null);
    }

    private static Field staticField(String name) throws Exception {
        Field f = DexKitLocator.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void setStatic(String name, Object value) throws Exception {
        staticField(name).set(null, value);
    }

    // 以下键拼装与 DexKitLocator 私有实现保持一致（白盒契约，见类注释）。

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

    private static String classKey(String className, String... anchors) {
        return "c\u0002" + (className == null ? "" : className) + "\u0002" + anchorKey(anchors);
    }

    private static String methodKey(String cls, String name, Class<?> returnType,
                                    Class<?>[] params, String... anchors) {
        StringBuilder sig = new StringBuilder();
        if (params != null) {
            for (Class<?> type : params) {
                if (sig.length() > 0) {
                    sig.append(',');
                }
                sig.append(type == null ? "?" : type.getName());
            }
        }
        return "m\u0002" + (cls == null ? "" : cls) + "\u0002" + (name == null ? "" : name)
                + "\u0002" + (returnType == null ? "" : returnType.getName())
                + "\u0002" + sig + "\u0002" + anchorKey(anchors);
    }
}
