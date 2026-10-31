package com.cloudmusictool.app;

import com.cloudmusictool.app.hooks.Hooks;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Hook 注册表完整性：所有非 blocked Feature 必须有 installer，blocked Feature 不得注册。
 * 以及安装异常/可选挂载点缺失必须进入 HookState 的 expected/failed/degraded 账本。
 *
 * Hooks 位于 com.cloudmusictool.app.hooks，installerKeys() 按设计是 package-private；
 * 本测试按任务约定放在 com.cloudmusictool.app 包，因此用反射读取键集合，
 * 不为了测试放宽生产代码可见性。
 */
public class HookRegistryTest {

    @Test
    public void everyNonBlockedFeatureHasInstaller() throws Exception {
        Set<Feature> registered = installerKeys();
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("缺少 installer: " + f.id + " (" + f.key + ")",
                    registered.contains(f));
        }
    }

    @Test
    public void blockedFeaturesAreNotInstalled() throws Exception {
        Set<Feature> registered = installerKeys();
        int blocked = 0;
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                continue;
            }
            blocked++;
            assertFalse("blocked Feature 不应注册 installer: " + f.id,
                    registered.contains(f));
        }
        assertTrue("应至少有一个 blocked Feature 用于校验该规则", blocked > 0);
    }

    /**
     * installerKeys() 必须与「未 blocked 的 Feature」集合完全一致，且返回的是一次性只读快照：
     * 之后对注册表的增删不得回写已取出的集合。Set/EnumSet 自身去重，
     * 长度相等、contains(null) 这类断言恒真，发现不了注册表漂移。
     */
    @Test
    public void installerKeysMatchesNonBlockedFeaturesAndIsReadOnlySnapshot() throws Exception {
        Set<Feature> expected = EnumSet.noneOf(Feature.class);
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                expected.add(f);
            }
        }
        assertFalse("应至少有一个非 blocked Feature", expected.isEmpty());

        Set<Feature> snapshot = installerKeys();
        assertEquals("installer 注册表必须与未 blocked 的 Feature 集合完全一致",
                expected, snapshot);

        Map<Feature, Object> installers = installers();
        Feature probe = Feature.DRAWER; // blocked：临时插入，验证快照语义后必须恢复
        Object original = installers.get(probe);
        try {
            installers.put(probe, throwingInstaller());
            assertFalse("已取出的键集合必须是快照，不受注册表后续改动影响",
                    snapshot.contains(probe));
            assertTrue("重新读取注册表必须反映新增键", installerKeys().contains(probe));
        } finally {
            if (original == null) {
                installers.remove(probe);
            } else {
                installers.put(probe, original);
            }
        }
        assertFalse("临时探测键必须恢复", installerKeys().contains(probe));
        assertThrows("installerKeys() 必须只读", UnsupportedOperationException.class,
                () -> snapshot.add(probe));
    }

    /**
     * installOne 的 catch 必须把「安装异常」记成 critical 失败：否则 failed/expected 都为 0，
     * 整功能失败既进不了诊断卡也进不了 App 的失败横幅（ReportProtocol 只在 expected&gt;0 时统计）。
     */
    @Test
    public void installThrowsIsRecordedAsCriticalFailure() throws Exception {
        Feature f = Feature.DARK_MODE;
        int failedBefore = featureInt(f, "failed");
        int expectedBefore = featureInt(f, "expected");

        HookState.enable(f);
        Map<Feature, Object> installers = installers();
        Object original = installers.get(f);
        try {
            installers.put(f, throwingInstaller());
            invokeInstallOne(f);
        } finally {
            installers.put(f, original);
        }
        HookState.finish(f);

        JSONObject after = featureJson(f);
        assertNotNull("安装异常后必须出现在回传快照里", after);
        assertEquals("安装异常必须计入 failed", failedBefore + 1, after.getInt("failed"));
        assertTrue("安装异常按 critical 记录", after.getInt("criticalFailed") >= 1);
        assertTrue("整功能失败必须进入 degraded", after.getBoolean("degraded"));
        assertTrue("一个挂载点都没装上时 unavailable", after.getBoolean("unavailable"));
        assertEquals("安装异常", after.getString("lastFailure"));
        assertTrue("expected 必须覆盖失败的挂载点",
                after.getInt("expected") >= expectedBefore + 1);
    }

    /**
     * 可选挂载点缺失（OaidHooks 等 quiet 定位）必须记入 failed/lastFailure，
     * 但非 critical，不触发 degraded——避免可选挂载点漂移把功能整体判死。
     */
    @Test
    public void missingMountIsRecordedWithoutDegrading() throws Exception {
        Feature f = Feature.CLIPBOARD;
        HookState.enable(f);
        HookState.installed(f, "ClipboardHooks#m1");
        HookState.finish(f);
        int failedBefore = featureInt(f, "failed");
        int expectedBefore = featureInt(f, "expected");

        Method markMountMissing = Class.forName("com.cloudmusictool.app.hooks.HookKit")
                .getDeclaredMethod("markMountMissing",
                        XposedModule.class, Feature.class, String.class);
        markMountMissing.setAccessible(true);
        markMountMissing.invoke(null, loggingModule(), f, "ClipboardHooks#m2");
        HookState.finish(f);

        JSONObject after = featureJson(f);
        assertEquals(failedBefore + 1, after.getInt("failed"));
        assertEquals("挂载点未找到 ClipboardHooks#m2", after.getString("lastFailure"));
        assertEquals(0, after.getInt("criticalFailed"));
        assertFalse("非 critical 缺失不触发 degraded", after.getBoolean("degraded"));
        assertFalse("已装上一个挂载点，功能不应 unavailable", after.getBoolean("unavailable"));
        assertTrue("expected 必须覆盖缺失挂载点",
                after.getInt("expected") >= expectedBefore + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<Feature, Object> installers() throws Exception {
        Field field = Hooks.class.getDeclaredField("INSTALLERS");
        field.setAccessible(true);
        return (Map<Feature, Object>) field.get(null);
    }

    /** Hooks.Installer 是包私有接口：用动态代理制造「安装即抛」的 installer。 */
    private static Object throwingInstaller() throws Exception {
        Class<?> installerType = Class.forName("com.cloudmusictool.app.hooks.Hooks$Installer");
        return Proxy.newProxyInstance(installerType.getClassLoader(),
                new Class<?>[]{installerType},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        throw new IllegalStateException("boom");
                    }
                });
    }

    private static void invokeInstallOne(Feature f) throws Exception {
        Method m = Hooks.class.getDeclaredMethod("installOne",
                XposedModule.class, ClassLoader.class, Feature.class, Config.class);
        m.setAccessible(true);
        m.invoke(null, loggingModule(), HookRegistryTest.class.getClassLoader(), f, null);
    }

    private static XposedModule loggingModule() throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("log".equals(method.getName())) {
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, new Runnable() {
            @Override
            public void run() {
            }
        });
        return module;
    }

    private static JSONObject featureJson(Feature f) throws Exception {
        Method m = HookState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        String json = (String) m.invoke(null);
        return new JSONObject(json).getJSONObject("features").optJSONObject(f.name());
    }

    private static int featureInt(Feature f, String key) throws Exception {
        JSONObject o = featureJson(f);
        return o == null ? 0 : o.optInt(key, 0);
    }

    @SuppressWarnings("unchecked")
    private static Set<Feature> installerKeys() throws Exception {
        Method method = Hooks.class.getDeclaredMethod("installerKeys");
        method.setAccessible(true);
        return (Set<Feature>) method.invoke(null);
    }
}
