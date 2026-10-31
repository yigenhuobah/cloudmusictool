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

/**
 * 深度复核 id 44 回归：quiet 定位（findMethodQuiet / findByNameQuiet / findByNameAndArity）
 * 失败的可选挂载点必须显式记账（HookState.markNotFound 非 critical + note），
 * 不能既不进 expected 也不进 failed——发现点在 OaidHooks，同类写法必须同步覆盖
 * LogTaskHooks / ApmHooks / StatisticHooks。
 *
 * 宿主类用测试源码里的 com.netease.cloudmusic.* 夹具：HookKit.findClass 走
 * Class.forName 能命中夹具类，但夹具缺少目标方法，逼出「类存在、方法定位失败」分支。
 */
public class QuietMountMissingAccountingTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Before
    public void resetProfile() {
        // findClass / findMethodQuiet 的 Profile 解析依赖活动 Profile；单测保持未适配状态。
        ProfileRegistry.setActive(null);
    }

    @Test
    public void logTaskHooks_missingInitIsRecordedPerMount() throws Exception {
        Feature f = Feature.LOG_TASKS;
        int failedBefore = featureInt(f, "failed");
        int expectedBefore = featureInt(f, "expected");

        HookState.enable(f);
        try {
            LogTaskHooks.install(module, QuietMountMissingAccountingTest.class.getClassLoader());
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("NeedLogToFileTask#init 与 NBLogTask#init 两个挂载点都要记账",
                failedBefore + 2, after.getInt("failed"));
        assertEquals("expected 必须覆盖缺失的挂载点", expectedBefore + 2, after.getInt("expected"));
        assertEquals("最后一条缺失是 NBLogTask#init",
                "挂载点未找到 NBLogTask#init", after.getString("lastFailure"));
        assertEquals("可选挂载点缺失不算 critical", 0, after.getInt("criticalFailed"));
        assertFalse("非 critical 缺失不触发 degraded", after.getBoolean("degraded"));
    }

    @Test
    public void apmHooks_missingApmMountsAreRecorded() throws Exception {
        Feature f = Feature.APM;
        int failedBefore = featureInt(f, "failed");
        int expectedBefore = featureInt(f, "expected");

        HookState.enable(f);
        try {
            ApmHooks.install(module, QuietMountMissingAccountingTest.class.getClassLoader());
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("logApm 与 forceApmUpload 两个挂载点都要记账",
                failedBefore + 2, after.getInt("failed"));
        assertEquals("expected 必须覆盖缺失的挂载点", expectedBefore + 2, after.getInt("expected"));
        assertEquals("最后一条缺失是 StatisticImpl#forceApmUpload",
                "挂载点未找到 StatisticImpl#forceApmUpload", after.getString("lastFailure"));
        assertEquals(0, after.getInt("criticalFailed"));
        assertFalse(after.getBoolean("degraded"));
    }

    @Test
    public void statisticHooks_missingObserveAndMonitorMountsAreRecorded() throws Exception {
        Feature f = Feature.STATS_OBSERVE;
        int failedBefore = featureInt(f, "failed");
        int expectedBefore = featureInt(f, "expected");

        HookState.enable(f);
        try {
            StatisticHooks.install(module, QuietMountMissingAccountingTest.class.getClassLoader());
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        // log + logJSON + LogMonitorConfigManager#b/c/d/e
        assertEquals("6 个挂载点都要记账", failedBefore + 6, after.getInt("failed"));
        assertEquals("expected 必须覆盖缺失的挂载点", expectedBefore + 6, after.getInt("expected"));
        assertEquals("最后一条缺失是 LogMonitorConfigManager#e",
                "挂载点未找到 LogMonitorConfigManager#e", after.getString("lastFailure"));
        assertEquals(0, after.getInt("criticalFailed"));
        assertFalse(after.getBoolean("degraded"));
    }

    /**
     * 反向对照：夹具类上存在的方法 quiet 定位必须命中——保证上面的用例确实走在
     * 「类找到（Class.forName 命中夹具）、方法缺失」分支，而不是类都没找到。
     */
    @Test
    public void fixtureClassSupportsQuietLookupHitPath() throws Exception {
        Method hit = HookKit.findMethodQuiet(
                com.netease.cloudmusic.process.task.app.create.NeedLogToFileTask.class,
                "execute");
        assertEquals("execute", hit.getName());
        assertEquals(
                com.netease.cloudmusic.process.task.app.create.NeedLogToFileTask.class,
                hit.getDeclaringClass());
    }

    // ---------------------------------------------------------------- 工具

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
}
