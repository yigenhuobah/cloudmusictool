package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.profile.ProfileRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 深度复核 id 3 回归：EN-01 的只读探针（sg.s / hc）必须与 installProbe 的
 * countState=false 对齐——探针类/方法缺失只打日志，不写 HookState 的
 * failed/expected/lastFailure，否则探针缺失会把主 hook 的健康诊断污染成失败。
 */
public class ProbeLookupAccountingTest {

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
    public void probeLookupsDoNotTouchHookState() throws Exception {
        Feature f = Feature.MINE_BACKGROUND;
        JSONObject before = featureJson(f);
        int failedBefore = before.optInt("failed", 0);
        int expectedBefore = before.optInt("expected", 0);
        String lastFailureBefore = before.optString("lastFailure", null);

        HookState.enable(f);
        try {
            assertNull("探针方法缺失仍返回 null",
                    HookKit.findMethodQuietProbe(module, f, "ProbeFixture#missing",
                            ProbeFixture.class, "missing", String.class));
            assertNull("探针按名+参数个数缺失仍返回 null",
                    HookKit.findByNameAndArityProbe(module, f, "ProbeFixture#missingArity",
                            ProbeFixture.class, "missingArity", 1));
            assertNull("探针类缺失仍返回 null",
                    HookKit.findClassProbe(module, f,
                            ProbeLookupAccountingTest.class.getClassLoader(),
                            "com.example.NoSuchProbeClass"));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("probe 缺失不得计入 failed", failedBefore, after.getInt("failed"));
        assertEquals("probe 缺失不得计入 expected", expectedBefore, after.getInt("expected"));
        assertEquals("probe 缺失不得改写 lastFailure",
                lastFailureBefore, after.optString("lastFailure", null));
        assertTrue("probe 方法缺失仍要打日志便于真机排查: " + module.logs,
                logContains(module, "probe miss: method not found"));
        assertTrue("probe 类缺失仍要打日志便于真机排查: " + module.logs,
                logContains(module, "probe miss: class not found"));
    }

    @Test
    public void installProxyProbesDoesNotPolluteEn01Diagnostics() throws Exception {
        Feature f = Feature.MINE_BACKGROUND;
        JSONObject before = featureJson(f);
        int failedBefore = before.optInt("failed", 0);
        int expectedBefore = before.optInt("expected", 0);
        String lastFailureBefore = before.optString("lastFailure", null);

        HookState.enable(f);
        try {
            Method install = MineBackgroundHooks.class.getDeclaredMethod("installProxyProbes",
                    XposedModule.class, Feature.class, ClassLoader.class, Class.class);
            install.setAccessible(true);
            install.invoke(null, module, f,
                    ProbeLookupAccountingTest.class.getClassLoader(), ProxyWithoutHc.class);
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("探针缺失不得计入 failed", failedBefore, after.getInt("failed"));
        assertEquals("探针缺失不得计入 expected", expectedBefore, after.getInt("expected"));
        assertEquals("探针缺失不得改写 lastFailure",
                lastFailureBefore, after.optString("lastFailure", null));
        assertTrue("EN-01 探针缺失必须留下日志: " + module.logs,
                logContains(module, "probe miss: method not found"));
    }

    // ---------------------------------------------------------------- 夹具

    /** 探针查找的目标类：故意不提供 missing / missingArity。 */
    static final class ProbeFixture {
        public void present() {
        }
    }

    /** 9.6.13 的 MyMusicProxyFragment 探针目标：故意不提供 hc(Continuation)。 */
    static final class ProxyWithoutHc {
    }

    // ---------------------------------------------------------------- 工具

    private static boolean logContains(HookTestSupport.FakeModule module, String fragment) {
        for (String log : module.logs) {
            if (log.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject featureJson(Feature f) throws Exception {
        Method m = HookState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        String json = (String) m.invoke(null);
        JSONObject features = new JSONObject(json).optJSONObject("features");
        JSONObject feature = features == null ? null : features.optJSONObject(f.name());
        return feature == null ? new JSONObject() : feature;
    }
}
