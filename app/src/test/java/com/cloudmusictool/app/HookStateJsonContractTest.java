package com.cloudmusictool.app;

import org.json.JSONArray;
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

    /** 诊断页读取根级 abtest 概要（DiagnosticsFragment 的 installed/failed/lastFailure）。 */
    @Test
    public void abtestSummaryKeys_consumedByDiagnostics() throws Exception {
        HookState.init("com.example.module", "main");
        JSONObject before = new JSONObject(buildJson()).optJSONObject("abtest");
        int installedBefore = before == null ? 0 : before.optInt("installed", 0);
        int failedBefore = before == null ? 0 : before.optInt("failed", 0);

        HookState.abTestInstalled("AbTestHooks#install");
        HookState.abTestNotFound("AbTestHooks#missing");
        HookState.abTestNotFound("AbTestHooks#missing"); // 同 label 只记一次
        HookState.noteAbTest("findClass failed: AbTestHooks#missing");

        JSONObject abtest = new JSONObject(buildJson()).getJSONObject("abtest");
        assertTrue("诊断页按字符串读 installed", abtest.has("installed"));
        assertTrue("诊断页按字符串读 failed", abtest.has("failed"));
        assertTrue("诊断页按字符串读 lastFailure", abtest.has("lastFailure"));
        assertEquals(installedBefore + 1, abtest.getInt("installed"));
        assertEquals(failedBefore + 1, abtest.getInt("failed"));
        assertEquals("findClass failed: AbTestHooks#missing",
                abtest.getString("lastFailure"));
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
     * 生产端会话标识必须是 "s&lt;启动毫秒&gt;-&lt;8 位随机 hex&gt;"：读取侧按字符串身份
     * 判定新会话，随机后缀保证时钟回拨/同毫秒重启时也不会与上一会话撞号。
     */
    @Test
    public void sessionCarriesProcessRandomSuffix() {
        assertTrue("session 形如 s<millis>-<8 位 hex>，实际：" + HookState.SESSION,
                HookState.SESSION.matches("s\\d{10,}-[0-9a-f]{8}"));
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
        HookState.recordCrash(1_700_000_000_000L, "main", "java.lang.Error CompactMarker");

        JSONObject compact = new JSONObject(HookState.buildJson(0));
        assertTrue("超限必须回退为摘要", compact.getBoolean("truncated"));
        assertTrue("摘要也必须携带 session", compact.has("session"));
        assertEquals(HookState.SESSION, compact.getString("session"));
        assertTrue("摘要也必须携带 report_time", compact.has("updatedAt"));
        assertTrue(compact.getLong("updatedAt") > 0L);
        assertEquals("main", compact.getString("process"));
        assertFalse("摘要不携带逐功能明细", compact.has("features"));
        assertTrue("摘要分支同样保留崩溃证据（体积极小，≤3×240 字符）",
                compact.has("crashes"));
        assertTrue(compact.getJSONArray("crashes").getString(0).contains("CompactMarker"));
    }

    // ---------------------------------------------------------- crashes 通道（内置诊断）

    /**
     * crashes 通道：进程内只保留最近 {@code ReportProtocol.MAX_CRASH_ENTRIES} 条，
     * buildJson 根级数组 [0] 为最新。连录 4 条（多于上限）保证旧 residue 一并被顶掉，
     * 用例不依赖 JUnit 方法顺序。
     */
    @Test
    public void crashesChannel_keepsLatestThreeNewestFirst() throws Exception {
        HookState.init("com.example.module", "main");
        long base = 1_700_000_000_000L;
        HookState.recordCrash(base, "main", "java.lang.IllegalStateException CrashA");
        HookState.recordCrash(base + 1, "pool-1", "java.lang.IllegalStateException CrashB");
        HookState.recordCrash(base + 2, "main", "java.lang.IllegalStateException CrashC");
        HookState.recordCrash(base + 3, "glow", "java.lang.IllegalStateException CrashD");

        JSONArray crashes = new JSONObject(buildJson()).getJSONArray("crashes");
        assertEquals("至多保留 MAX_CRASH_ENTRIES 条",
                ReportProtocol.MAX_CRASH_ENTRIES, crashes.length());
        assertTrue("crashes[0] 必须是最新一条（新的顶掉最旧的）",
                crashes.getString(0).contains("CrashD"));
        assertTrue(crashes.getString(1).contains("CrashC"));
        assertTrue(crashes.getString(2).contains("CrashB"));
        // 条目必须携带线程名与异常类名（时间前缀由格式化保证，不做本地化脆弱断言）。
        assertTrue(crashes.getString(0).contains("glow"));
        assertTrue(crashes.getString(0).contains("java.lang.IllegalStateException"));
    }

    /** 单条截断 ≤ HookState.MAX_CRASH_CHARS（240），空摘要整条忽略（不能顶掉已有条目）。 */
    @Test
    public void crashEntry_truncatesTo240AndIgnoresEmptySummary() throws Exception {
        HookState.init("com.example.module", "main");
        HookState.recordCrash(1_700_000_000_000L, "main", "x".repeat(400));
        HookState.recordCrash(1_700_000_000_001L, "main", null);
        HookState.recordCrash(1_700_000_000_002L, "main", "");

        JSONArray crashes = new JSONObject(buildJson()).getJSONArray("crashes");
        String newest = crashes.getString(0);
        assertEquals("单条截断 ≤ 240 字符", HookState.MAX_CRASH_CHARS, newest.length());
        assertTrue("截断以省略号收尾", newest.endsWith("…"));
        assertTrue("空摘要必须整条忽略：最新位仍是 x 条目", newest.contains("xxx"));
    }

    /** crashSummary = 异常类名 + 栈前 2 帧；第 3 帧起截掉，null 兜底空串。 */
    @Test
    public void crashSummary_classNameAndAtMostTwoFrames() {
        RuntimeException e = new RuntimeException("boom");
        e.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.a.B", "run", "B.java", 10),
                new StackTraceElement("com.a.C", "go", "C.java", 20),
                new StackTraceElement("com.a.D", "loop", "D.java", 30)});

        String summary = HookState.crashSummary(e);
        assertTrue("以异常类名开头", summary.startsWith("java.lang.RuntimeException"));
        assertTrue("包含第 1 帧", summary.contains("com.a.B.run(B.java:10)"));
        assertTrue("包含第 2 帧", summary.contains("com.a.C.go(C.java:20)"));
        assertFalse("第 3 帧起截掉", summary.contains("com.a.D"));
        assertEquals("null 兜底空串（recordCrash 会整条忽略）",
                "", HookState.crashSummary(null));
    }
}
