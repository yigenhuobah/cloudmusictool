package com.cloudmusictool.app.hooks;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
}
