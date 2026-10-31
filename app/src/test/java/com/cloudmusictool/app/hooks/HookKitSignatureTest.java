package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.profile.ProfileRegistry;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Profile 映射方法的签名校验：映射分支必须和常规分支一样，拒绝参数/返回类型
 * 与调用方期望不一致的重载，避免 Profile 漂移时把 hook 挂到错误方法上。
 */
public class HookKitSignatureTest {

    @After
    public void resetProfile() {
        // ProfileRegistry 是静态状态，避免其它用例污染「未适配版本」分支。
        ProfileRegistry.setActive(null);
    }

    @Test
    public void nullExpectationsAcceptAnyMethod() throws Exception {
        assertTrue(HookKit.matchesExpected(method("noArg"), null, null));
    }

    @Test
    public void paramsMustMatchExactly() throws Exception {
        assertTrue(HookKit.matchesExpected(
                method("stringArg", String.class), null, new Class<?>[]{String.class}));
        assertFalse(HookKit.matchesExpected(
                method("stringArg", String.class), null, new Class<?>[]{int.class}));
        assertFalse(HookKit.matchesExpected(
                method("stringArg", String.class), null, new Class<?>[]{}));
    }

    @Test
    public void returnTypeMustMatchWhenSpecified() throws Exception {
        assertTrue(HookKit.matchesExpected(
                method("boolInt", int.class), boolean.class, new Class<?>[]{int.class}));
        assertFalse(HookKit.matchesExpected(
                method("boolInt", int.class), void.class, new Class<?>[]{int.class}));
    }

    @Test
    public void nullMethodRejected() {
        assertFalse(HookKit.matchesExpected(null, null, null));
    }

    /** hookNull 挂到基本类型方法时必须拒绝安装，否则宿主拆箱 null 会崩溃。 */
    @Test
    public void hookNullRejectsPrimitiveReturnType() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        HookKit.hookNull(loggingModule(logs), Feature.COMMENT_AD, Fixture.class, "primitiveInt");
        String joined = String.join("\n", logs);
        assertTrue("应记录 nullReturn 类型不匹配: " + joined,
                joined.contains("nullReturn requires reference or void"));
        assertFalse("类型不匹配时不得注册 hook: " + joined, joined.contains("registered"));
    }

    /** 引用类型与 void 仍允许 hookNull（返回 null 是合法值）。 */
    @Test
    public void hookNullAcceptsReferenceAndVoidReturnTypes() throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        HookKit.hookNull(loggingModule(logs), Feature.COMMENT_AD, Fixture.class, "stringReturn");
        HookKit.hookNull(loggingModule(logs), Feature.COMMENT_AD, Fixture.class, "noArg");
        String joined = String.join("\n", logs);
        assertFalse("引用/void 返回值不应被判为类型不匹配: " + joined,
                joined.contains("return type mismatch"));
    }

    /** 未适配版本（无活动 Profile）不进 DexKit 路径：避免主线程首次全盘扫描。 */
    @Test
    public void findClassSkipsDexKitWithoutActiveProfile() throws Exception {
        ProfileRegistry.setActive(null);
        List<String> logs = new CopyOnWriteArrayList<>();
        Class<?> found = HookKit.findClass(loggingModule(logs), Feature.SPLASH_AD,
                HookKitSignatureTest.class.getClassLoader(), "com.example.Missing", "anchor");
        assertNull(found);
        String joined = String.join("\n", logs);
        assertTrue("应显式记录跳过 DexKit: " + joined,
                joined.contains("skip DexKit (no active profile)"));
        assertFalse("未适配版本不得执行 DexKit 兜底: " + joined,
                joined.contains("slow path: class"));
    }

    /** 无 Feature 归属的引导钩子用显式前缀，不能再被误读成 ABTEST。 */
    @Test
    public void installUnscopedUsesExplicitPrefix() throws Exception {
        List<String> ids = new CopyOnWriteArrayList<>();
        List<String> logs = new CopyOnWriteArrayList<>();
        boolean ok = HookKit.installUnscoped(capturingModule(logs, ids), "RUNTIME",
                "runtime/bootstrap", method("stringReturn"), chain -> null);
        assertTrue("安装应成功", ok);
        assertEquals(Arrays.asList("RUNTIME#runtime/bootstrap"), ids);
        String joined = String.join("\n", logs);
        assertTrue("注册日志应带显式前缀: " + joined,
                joined.contains("RUNTIME registered runtime/bootstrap"));
        assertFalse("不得复用 ABTEST 前缀: " + joined, joined.contains("ABTEST"));
    }

    /** XposedModule 借 no-arg 构造 + attachFramework(代理) 承接日志；hook 直接抛错由 install 兜住。 */
    private static XposedModule loggingModule(final List<String> sink) throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("log".equals(method.getName())) {
                            sink.add((String) args[2]);
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

    /** hook 成功路径：HookBuilder 代理记录 setId，其余链式方法原样返回。 */
    private static XposedModule capturingModule(final List<String> logs,
                                                final List<String> ids) throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("log".equals(name)) {
                            logs.add((String) args[2]);
                            return null;
                        }
                        if ("hook".equals(name)) {
                            return Proxy.newProxyInstance(
                                    XposedInterface.HookBuilder.class.getClassLoader(),
                                    new Class<?>[]{XposedInterface.HookBuilder.class},
                                    new InvocationHandler() {
                                        @Override
                                        public Object invoke(Object b, Method bm, Object[] ba) {
                                            String builderMethod = bm.getName();
                                            if ("setId".equals(builderMethod)) {
                                                ids.add((String) ba[0]);
                                                return b;
                                            }
                                            if ("intercept".equals(builderMethod)) {
                                                return null;
                                            }
                                            return b;
                                        }
                                    });
                        }
                        throw new UnsupportedOperationException(name);
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

    private static Method method(String name, Class<?>... params) throws Exception {
        return Fixture.class.getDeclaredMethod(name, params);
    }

    @SuppressWarnings("unused")
    private static final class Fixture {
        void noArg() {
        }

        void stringArg(String value) {
        }

        boolean boolInt(int value) {
            return true;
        }

        int primitiveInt() {
            return 0;
        }

        String stringReturn() {
            return null;
        }

        String stringIntLong(int first, long second) {
            return null;
        }
    }
}
