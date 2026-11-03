package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.ProfileRegistry;
import com.cloudmusictool.app.profile.TargetProfile;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 深度复核 id 1 回归：quiet 定位先失败、随后 fallback 找到同一挂载点并安装成功时，
 * 必须先前的 failed 记录被撤销，否则诊断页会同时显示 installed 与 failed，
 * 并保留一条已经恢复的 skip 原因。
 *
 * <p>夹具类存在、只缺带参重载，精确复现 OaidHooks / ApmHooks / StatisticHooks /
 * AutoPlayHooks / PendantHooks / HintHooks / TopTabHooks 的「quiet miss -> 按名兜底
 * -> 安装同一 label」路径。
 */
public class QuietFallbackResolutionTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Before
    public void resetProfile() {
        ProfileRegistry.setActive(null);
    }

    @After
    public void clearProfile() {
        ProfileRegistry.setActive(null);
    }

    @Test
    public void fallbackInstallAfterQuietMissClearsFailureRecord() throws Exception {
        // 独立 Feature，避免与其它用例共享 HookState 静态计数。
        Feature f = Feature.PLAYER_HINTS;
        JSONObject before = featureJson(f);
        int failedBefore = before.optInt("failed", 0);
        int installedBefore = before.optInt("installed", 0);

        HookState.enable(f);
        try {
            assertNull("夹具没有 o(String)，quiet 定位必须先失败",
                    HookKit.findMethodQuiet(module, f, "Fixture#o",
                            Fixture.class, "o", String.class));
            assertEquals("quiet miss 先记一条失败",
                    failedBefore + 1, featureInt(f, "failed"));
            assertEquals("失败原因带挂载点",
                    "挂载点未找到 Fixture#o", featureJson(f).optString("lastFailure"));

            Method fallback = HookKit.findByNameQuiet(module, f, "Fixture#o",
                    Fixture.class, "o");
            assertNotNull("按名 fallback 应找到无参 o()", fallback);
            assertTrue("fallback 命中后应安装成功",
                    HookKit.install(hookableModule(), f, "Fixture#o", fallback,
                            HookKit.skipVoid(module, f, "fixture.o")));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("同一挂载点安装成功后必须撤销 quiet miss 的假失败",
                failedBefore, after.getInt("failed"));
        assertEquals(installedBefore + 1, after.getInt("installed"));
        assertEquals("expected 必须回到 installed+failed，不得因假失败虚高",
                after.getInt("installed") + after.getInt("failed"),
                after.getInt("expected"));
        assertFalse("已恢复挂载点的 skip 原因不得继续显示",
                "挂载点未找到 Fixture#o".equals(after.optString("lastFailure")));
    }

    /**
     * critical 版本的同一路径：quiet miss 先计 criticalFailed，fallback 安装成功后
     * 必须同时撤销 failed 与 criticalFailed，degraded 回到 false。
     */
    @Test
    public void fallbackInstallAfterCriticalQuietMissClearsCriticalFailure() throws Exception {
        Feature f = Feature.HINTS;
        ProfileRegistry.setActive(new TargetProfile(9006008L, "test",
                Collections.singletonList(MountSpec.method("UI-07", "Fixture#o", true,
                        Fixture.class.getName(), "o", "V", new String[0]))));

        JSONObject before = featureJson(f);
        int failedBefore = before.optInt("failed", 0);
        int criticalBefore = before.optInt("criticalFailed", 0);

        HookState.enable(f);
        try {
            assertNull(HookKit.findMethodQuiet(module, f, "Fixture#o",
                    Fixture.class, "o", String.class));
            assertEquals("critical quiet miss 先计入 criticalFailed",
                    criticalBefore + 1, featureInt(f, "criticalFailed"));

            Method fallback = HookKit.findByNameQuiet(module, f, "Fixture#o",
                    Fixture.class, "o");
            assertNotNull(fallback);
            assertTrue(HookKit.install(hookableModule(), f, "Fixture#o", fallback,
                    HookKit.skipVoid(module, f, "fixture.o")));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("安装成功后 critical 失败也必须撤销",
                criticalBefore, after.getInt("criticalFailed"));
        assertEquals(failedBefore, after.getInt("failed"));
        assertFalse("critical 失败撤销后 degraded 必须复位",
                after.getBoolean("degraded"));
    }

    /** 夹具：类可加载，只有无参 o()，缺少 quiet 查找的 o(String)。 */
    static final class Fixture {
        public void o() {
        }
    }

    // ---------------------------------------------------------------- 工具

    /** hook 成功路径的最小 XposedModule：hook() 返回可链式调用的 HookBuilder 代理。 */
    private static XposedModule hookableModule() throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("log".equals(name)) {
                            return null;
                        }
                        if ("hook".equals(name)) {
                            return Proxy.newProxyInstance(
                                    XposedInterface.HookBuilder.class.getClassLoader(),
                                    new Class<?>[]{XposedInterface.HookBuilder.class},
                                    new InvocationHandler() {
                                        @Override
                                        public Object invoke(Object builder, Method builderMethod,
                                                             Object[] builderArgs) {
                                            if ("intercept".equals(builderMethod.getName())) {
                                                return null;
                                            }
                                            return builder;
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

    private static JSONObject featureJson(Feature f) throws Exception {
        Method m = HookState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        String json = (String) m.invoke(null);
        JSONObject features = new JSONObject(json).optJSONObject("features");
        JSONObject feature = features == null ? null : features.optJSONObject(f.name());
        return feature == null ? new JSONObject() : feature;
    }

    private static int featureInt(Feature f, String key) throws Exception {
        return featureJson(f).optInt(key, 0);
    }
}
