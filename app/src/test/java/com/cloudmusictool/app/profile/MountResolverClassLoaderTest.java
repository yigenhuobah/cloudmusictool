package com.cloudmusictool.app.profile;

import com.netease.fixture.ClFixture;

import org.junit.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * MountResolver 缓存必须按 ClassLoader 隔离。
 *
 * 9.6.13 的 Tinker 运行时 ClassLoader 与启动阶段 base ClassLoader 会同时存在：
 * 同一个 MountSpec 在两个 loader 下解析出的 Class/Method 不是同一个对象，
 * 只按 spec.id 缓存会让运行时解析命中 base 的 Method，造成 hook 挂错类。
 */
public class MountResolverClassLoaderTest {

    private static final String FIXTURE = "com.netease.fixture.ClFixture";

    private static ClassLoader newIsolatedLoader() {
        URL location = ClFixture.class.getProtectionDomain().getCodeSource().getLocation();
        // parent=null 只用 bootstrap loader；fixture 不依赖测试 classpath 上的其他类。
        return new URLClassLoader(new URL[]{location}, null);
    }

    @Test
    public void sameSpecIsCachedPerClassLoader() throws Exception {
        MountSpec spec = MountSpec.method("TEST_CL_ISOLATION", "fixture", false,
                FIXTURE, "ping", "Ljava/lang/String;", new String[0]);
        ClassLoader cl1 = newIsolatedLoader();
        ClassLoader cl2 = newIsolatedLoader();

        Class<?> c1 = MountResolver.resolveClass(spec, cl1);
        Class<?> c2 = MountResolver.resolveClass(spec, cl2);
        assertNotNull(c1);
        assertNotNull(c2);
        assertSame(cl1, c1.getClassLoader());
        assertSame(cl2, c2.getClassLoader());
        assertNotSame("同一 spec 在不同 ClassLoader 下必须是不同 Class", c1, c2);
        assertSame("同一 ClassLoader 应命中缓存", c1, MountResolver.resolveClass(spec, cl1));
        assertSame(c2, MountResolver.resolveClass(spec, cl2));

        Method m1 = MountResolver.resolveMethod(spec, c1, cl1);
        Method m2 = MountResolver.resolveMethod(spec, c2, cl2);
        assertNotNull(m1);
        assertNotNull(m2);
        assertSame(c1, m1.getDeclaringClass());
        assertSame(c2, m2.getDeclaringClass());
        assertNotSame("同一 spec 在不同 ClassLoader 下不能共享 Method", m1, m2);
        assertSame(m1, MountResolver.resolveMethod(spec, c1, cl1));
        assertSame(m2, MountResolver.resolveMethod(spec, c2, cl2));

        // 方法缓存以声明类的 ClassLoader 为准：即使调用方传错 cl，
        // 也不能把 c2 的解析结果串到 c1 的缓存里。
        assertSame(m2, MountResolver.resolveMethod(spec, c2, cl1));
    }

    /**
     * 同一 ClassLoader 下不同 Class 的同名同签名方法不能共享缓存：
     * 方法缓存键必须包含类名，否则后解析的 Class 会拿到前一个 Class 的 Method。
     */
    @Test
    public void sameLoaderDifferentClass_methodsAreNotShared() throws Exception {
        MountSpec spec = MountSpec.method("TEST_SAME_LOADER_DIFF_CLASS", "fixture", false,
                FIXTURE, "ping", "Ljava/lang/String;", new String[0]);
        ClassLoader cl = newIsolatedLoader();
        Class<?> first = MountResolver.resolveClass(spec, cl);
        Class<?> other = Class.forName(FIXTURE + "$Other", false, cl);
        assertNotNull(first);
        assertNotNull(other);

        Method m1 = MountResolver.resolveMethod(spec, first, cl);
        Method m2 = MountResolver.resolveMethod(spec, other, cl);
        assertNotNull(m1);
        assertNotNull(m2);
        assertSame(first, m1.getDeclaringClass());
        assertSame(other, m2.getDeclaringClass());
        assertNotSame("同 loader 不同 Class 不能共享 Method", m1, m2);
        assertSame(m1, MountResolver.resolveMethod(spec, first, cl));
        assertSame(m2, MountResolver.resolveMethod(spec, other, cl));
    }
}
