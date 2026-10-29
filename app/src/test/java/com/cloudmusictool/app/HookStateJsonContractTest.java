package com.cloudmusictool.app;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * HookState.buildJson 回传字段面的契约测试（双进程 stringly 协议的生产侧钉子）。
 *
 * DiagnosticsFragment.featureSummary 按字符串读 features.&lt;ENUM&gt;.{enabled,
 * installed, degraded, unavailable, hitCount, lastFailure}，全部 optX 带默认值
 * ——字段名漂移时诊断页静默显示 0/无数据（见 repro-testgaps-6：把 installed
 * 改名后 85 个测试全绿）。这里通过公开状态 API 驱动状态机，再反射调用私有
 * buildJson（纯 JVM），断言消费端依赖的键真实存在且值正确。
 * 各用例使用互不相同的 Feature，避免 HookState 静态状态在用例间串扰。
 */
public class HookStateJsonContractTest {

    private static String buildJson() throws Exception {
        Method m = HookState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    @Test
    public void featureSummaryConsumedKeys_present() throws Exception {
        HookState.init("com.example.module", "main");
        Feature f = Feature.TOP_CHANNELS;
        HookState.enable(f);                    // enabled = true
        HookState.installed(f, "m1");           // installed=1, expected=1
        HookState.markNotFound(f, "m2", true);  // failed=1, criticalFailed=1, expected=2
        HookState.hit(f);                       // hit=true, hitCount=1
        HookState.note(f, "findMethod failed: m2");
        HookState.finish(f);                    // degraded=true, unavailable=false

        JSONObject root = new JSONObject(buildJson());
        assertTrue(root.has("updatedAt"));
        assertTrue("session 是读取侧会话门禁的根键", root.has("session"));
        assertEquals(HookState.SESSION, root.getString("session"));
        assertEquals("main", root.getString("process"));
        assertTrue(root.has("features"));

        JSONObject o = root.getJSONObject("features").getJSONObject(f.name());
        // DiagnosticsFragment.featureSummary 消费的六个键。
        assertTrue(o.has("enabled"));
        assertTrue(o.getBoolean("enabled"));
        assertTrue(o.has("installed"));
        assertEquals(1, o.getInt("installed"));
        assertTrue(o.has("degraded"));
        assertTrue(o.getBoolean("degraded"));
        assertTrue(o.has("unavailable"));
        assertFalse(o.getBoolean("unavailable"));
        assertTrue(o.has("hitCount"));
        assertEquals(1, o.getInt("hitCount"));
        assertTrue(o.has("lastFailure"));
        assertEquals("findMethod failed: m2", o.getString("lastFailure"));
        // buildJson 字段面的其余字段。
        assertEquals(f.id, o.getString("id"));
        assertEquals(2, o.getInt("expected"));
        assertEquals(1, o.getInt("failed"));
        assertEquals(1, o.getInt("criticalFailed"));
        assertTrue(o.getBoolean("hit"));
    }

    @Test
    public void unavailableFeature_markedWhenNothingInstalled() throws Exception {
        HookState.init("com.example.module", "main");
        Feature f = Feature.STARTUP_ORCHESTRATION;
        HookState.enable(f);
        HookState.markNotFound(f, "class:X", false);
        HookState.finish(f); // installed == 0 -> unavailable

        JSONObject o = new JSONObject(buildJson())
                .getJSONObject("features").getJSONObject(f.name());
        assertTrue(o.getBoolean("unavailable"));
        assertFalse(o.getBoolean("degraded"));
        assertEquals(0, o.getInt("installed"));
        assertEquals(1, o.getInt("failed"));
    }

    @Test
    public void installedClearsUnavailableAndEntryIsIndependentFromHit() throws Exception {
        HookState.init("com.example.module", "main");
        Feature f = Feature.MINE_BACKGROUND;
        HookState.enable(f);
        HookState.markNotFound(f, "class:runtime-missing", false);
        HookState.finish(f); // installed == 0 -> unavailable

        JSONObject before = featureJson(f);
        assertTrue("启动阶段无挂载点时应为 unavailable", before.getBoolean("unavailable"));

        HookState.installed(f, "runtime:m1");
        JSONObject afterInstall = featureJson(f);
        assertFalse("installed>0 必须立即清除 unavailable",
                afterInstall.getBoolean("unavailable"));

        HookState.entry(f);
        HookState.entry(f);
        JSONObject afterEntry = featureJson(f);
        assertEquals("entry 独立计数", 2, afterEntry.getInt("entryCount"));
        assertEquals("entry 不计入 hitCount", 0, afterEntry.getInt("hitCount"));
        assertFalse("entry 不应置位 hit", afterEntry.getBoolean("hit"));

        HookState.hit(f);
        JSONObject afterHit = featureJson(f);
        assertEquals("hit 不影响 entryCount", 2, afterHit.getInt("entryCount"));
        assertEquals(1, afterHit.getInt("hitCount"));
        assertTrue(afterHit.getBoolean("hit"));

        // 运行时安装结束后重新 finish 不能把已安装的 Feature 写回 unavailable。
        HookState.finish(f);
        assertFalse(featureJson(f).getBoolean("unavailable"));
    }

    private static JSONObject featureJson(Feature f) throws Exception {
        return new JSONObject(buildJson())
                .getJSONObject("features").getJSONObject(f.name());
    }

    @Test
    public void rootSummaryKeys_present() throws Exception {
        HookState.init("com.example.module", null);
        Feature f = Feature.PENDANT;
        HookState.enable(f);
        HookState.installed(f, "m1");

        JSONObject root = new JSONObject(buildJson());
        assertTrue(root.has("updatedAt"));
        assertTrue("session 必须始终输出", root.has("session"));
        assertEquals(HookState.SESSION, root.getString("session"));
        assertFalse("process 未记录时不应输出", root.has("process"));
        assertTrue(root.has("enabledFeatures"));
        assertTrue(root.has("installed"));
        assertTrue(root.has("expected"));
        assertTrue(root.has("failed"));
        assertTrue(root.has("noHitFeatures"));
        assertTrue(root.has("degradedFeatures"));
        assertTrue(root.has("unavailableFeatures"));
        assertTrue(root.has("features"));
        assertFalse("未超 64KB 时不应截断", root.has("truncated"));
    }

    /**
     * 超限摘要分支：truncated=true 时仍必须输出 session 与 report_time（updatedAt），
     * 否则读取侧会把摘要误判为无会话/过期数据。用包内极小上限入口直达该分支。
     */
    @Test
    public void truncatedSummaryKeepsSessionAndReportTime() throws Exception {
        HookState.init("com.example.module", "main");
        Feature f = Feature.SPLASH_AD;
        HookState.enable(f);
        HookState.installed(f, "m1");

        JSONObject compact = new JSONObject(HookState.buildJson(0));
        assertTrue("超限必须回退为摘要", compact.getBoolean("truncated"));
        assertTrue("摘要也必须携带 session", compact.has("session"));
        assertEquals(HookState.SESSION, compact.getString("session"));
        assertTrue("摘要也必须携带 report_time", compact.has("updatedAt"));
        assertTrue(compact.getLong("updatedAt") > 0L);
        assertEquals("main", compact.getString("process"));
        assertFalse("摘要不携带逐功能明细", compact.has("features"));
    }
}
