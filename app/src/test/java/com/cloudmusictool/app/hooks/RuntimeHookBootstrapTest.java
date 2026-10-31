package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * RuntimeHookBootstrap 的运行时 ClassLoader 校验：只有能加载至少一个已知
 * 宿主类的 loader 才允许执行 installer；失败必须让后续 Activity 能重试。
 */
public class RuntimeHookBootstrapTest {

    /** 只允许白名单类加载，其余一律 ClassNotFoundException。 */
    private static final class ProbeLoader extends ClassLoader {
        private final Set<String> loadable;

        ProbeLoader(String... names) {
            super(null);
            loadable = new HashSet<>(Arrays.asList(names));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (loadable.contains(name)) {
                return Class.forName(name, false, RuntimeHookBootstrapTest.class.getClassLoader());
            }
            throw new ClassNotFoundException(name);
        }
    }

    /** 静态初始化一旦执行就失败；用于确认校验使用 initialize=false。 */
    public static final class StaticInitBomb {
        static {
            if (failInit()) {
                throw new IllegalStateException("unreachable");
            }
        }

        private static boolean failInit() {
            throw new IllegalStateException("must not initialize probe class");
        }
    }

    @Test
    public void loaderThatLoadsProbe_isUsable() {
        assertTrue(RuntimeHookBootstrap.runtimeLoaderUsable(
                new ProbeLoader("java.lang.String"), "java.lang.String"));
    }

    @Test
    public void loaderThatCannotLoadAnyProbe_isRejected() {
        assertFalse(RuntimeHookBootstrap.runtimeLoaderUsable(
                new ProbeLoader(), "probe.Missing1", "probe.Missing2"));
        assertFalse(RuntimeHookBootstrap.runtimeLoaderUsable(null, "probe.Missing"));
        assertFalse(RuntimeHookBootstrap.runtimeLoaderUsable(
                new ProbeLoader("java.lang.String")));
    }

    @Test
    public void laterProbeCanSatisfyValidation() {
        assertTrue(RuntimeHookBootstrap.runtimeLoaderUsable(
                new ProbeLoader("java.lang.Integer"), "probe.Missing", "java.lang.Integer"));
    }

    @Test
    public void blankProbeNamesAreIgnored() {
        assertFalse(RuntimeHookBootstrap.runtimeLoaderUsable(
                new ProbeLoader(), null, "", "probe.Missing"));
    }

    @Test
    public void validationDoesNotInitializeProbeClass() {
        assertTrue(RuntimeHookBootstrap.runtimeLoaderUsable(
                new ProbeLoader(StaticInitBomb.class.getName()), StaticInitBomb.class.getName()));
    }

    @Test
    public void defaultProbesContainKnownHostClass() {
        assertTrue(Arrays.asList(RuntimeHookBootstrap.RUNTIME_PROBE_CLASSES)
                .contains("com.netease.cloudmusic.meta.virtual.UserBackgroundInfo"));
    }

    /**
     * installer 抛异常必须按 critical 失败记账：否则 failed/expected 都为 0，
     * 完全失败的功能不会进入 App 失败横幅（ReportProtocol 只在 expected&gt;0 时统计）。
     */
    @Test
    public void installerFailureIsRecordedAsCriticalFailure() throws Exception {
        Feature f = Feature.OAID_REPORT;
        JSONObject before = featureJson(f);
        int failedBefore = before == null ? 0 : before.optInt("failed", 0);

        HookState.enable(f);
        RuntimeHookBootstrap.recordInstallFailure(loggingModule(), f,
                new IllegalStateException("boom"));
        HookState.finish(f);

        JSONObject after = featureJson(f);
        assertEquals(failedBefore + 1, after.getInt("failed"));
        assertTrue("安装异常按 critical 记录", after.getInt("criticalFailed") >= 1);
        assertTrue("整功能失败必须进入 degraded", after.getBoolean("degraded"));
        assertTrue("一个挂载点都没装上时 unavailable", after.getBoolean("unavailable"));
        assertEquals("运行时安装异常", after.getString("lastFailure"));
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
}
