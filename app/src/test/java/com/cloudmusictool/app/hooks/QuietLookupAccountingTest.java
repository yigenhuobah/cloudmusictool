package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.profile.ProfileRegistry;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 带 Feature/label 的 quiet 定位契约：方法缺失时必须 WARN + 非 critical 记账，
 * 让可选挂载点进入 expected/failed 与诊断页；旧的无上下文重载保持静默。
 *
 * <p>夹具类本身存在（Class 可解析），只缺目标方法，从而精确落在
 * 「类找到、方法定位失败」分支。
 */
public class QuietLookupAccountingTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Before
    public void resetProfile() {
        ProfileRegistry.setActive(null);
    }

    @Test
    public void missingMethodQuietLookupRecordsSkipReason() throws Exception {
        Feature f = Feature.AD_PRELOAD;
        int failedBefore = featureInt(f, "failed");
        int expectedBefore = featureInt(f, "expected");

        Method found;
        HookState.enable(f);
        try {
            found = HookKit.findMethodQuiet(module, f, "Fixture#missing",
                    Fixture.class, "missing");
        } finally {
            HookState.finish(f);
        }

        assertNull("夹具没有 missing 方法，必须返回 null", found);
        assertTrue("缺失必须打 WARN 说明定位失败: " + module.logs,
                logContains(module, "skip: method not found"));
        assertTrue("WARN 必须带上挂载点 label: " + module.logs,
                logContains(module, "Fixture#missing"));

        JSONObject after = featureJson(f);
        assertEquals("缺失挂载点必须计入 failed", failedBefore + 1, after.getInt("failed"));
        assertEquals("缺失挂载点必须计入 expected", expectedBefore + 1, after.getInt("expected"));
        assertEquals("skip 原因必须写进 lastFailure",
                "挂载点未找到 Fixture#missing", after.getString("lastFailure"));
        assertEquals("可选挂载点缺失不算 critical", 0, after.getInt("criticalFailed"));
        assertFalse("非 critical 缺失不触发 degraded", after.getBoolean("degraded"));
    }

    @Test
    public void allQuietLookupVariantsRecordMissingMethods() throws Exception {
        Feature f = Feature.FEED_AD;
        int failedBefore = featureInt(f, "failed");

        HookState.enable(f);
        try {
            assertNull(HookKit.findByNameQuiet(module, f, "Fixture#missingByName",
                    Fixture.class, "missingByName"));
            assertNull(HookKit.findByShape(module, f, "Fixture#missingByShape",
                    Fixture.class, "missingByShape", String.class));
            assertNull(HookKit.findByNameAndArity(module, f, "Fixture#missingByArity",
                    Fixture.class, "missingByArity", 2));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("四种 quiet 定位共用同一记账契约", failedBefore + 3, after.getInt("failed"));
        assertEquals("最后一个缺失项作为最近失败原因",
                "挂载点未找到 Fixture#missingByArity", after.getString("lastFailure"));
    }

    @Test
    public void deprecatedQuietLookupStaysSilent() throws Exception {
        Feature f = Feature.AD_PRELOAD;
        int failedBefore = featureInt(f, "failed");
        String lastFailureBefore = featureJson(f).optString("lastFailure", null);

        HookState.enable(f);
        try {
            assertNull("旧重载仍返回 null", HookKit.findMethodQuiet(
                    Fixture.class, "missingDeprecated"));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("无 Feature 上下文的旧重载不得记账", failedBefore, after.getInt("failed"));
        assertEquals("旧重载不得改写 lastFailure",
                lastFailureBefore, after.optString("lastFailure", null));
    }

    @Test
    public void installNullMethodRecordsSkipReason() throws Exception {
        Feature f = Feature.LAUNCH_DIALOG;
        int failedBefore = featureInt(f, "failed");
        int expectedBefore = featureInt(f, "expected");

        HookState.enable(f);
        boolean installed;
        try {
            installed = HookKit.install(module, f, "Fixture#install", (Method) null,
                    chain -> null);
        } finally {
            HookState.finish(f);
        }

        assertFalse("method 为 null 时安装必须失败", installed);
        assertTrue("install(null) 必须打 WARN: " + module.logs,
                logContains(module, "skip: method null"));

        JSONObject after = featureJson(f);
        assertEquals("install(null) 必须计入 failed", failedBefore + 1, after.getInt("failed"));
        assertEquals("install(null) 必须计入 expected",
                expectedBefore + 1, after.getInt("expected"));
        assertEquals("install(null) 的 skip 原因必须可见",
                "方法为空 Fixture#install", after.getString("lastFailure"));
    }

    // ---------------------------------------------------------------- 工具

    /** 夹具：类可加载，但不含上面查询的任何方法。 */
    static final class Fixture {
        void present() {
        }
    }

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
        return feature != null ? feature : new JSONObject();
    }

    private static int featureInt(Feature f, String key) throws Exception {
        return featureJson(f).optInt(key, 0);
    }
}
