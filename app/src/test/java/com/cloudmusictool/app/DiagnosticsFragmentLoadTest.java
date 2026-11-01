package com.cloudmusictool.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 诊断页两段式刷新的契约测试（{@link DiagnosticsFragment#loadAsync}）。
 *
 * <p>诊断页曾经在主线程同步读 hook_status（首次读取会加载磁盘）并重建整页；
 * 这里固定“读取在后台、渲染只经主线程投递、过期结果丢弃”三条语义。
 */
public class DiagnosticsFragmentLoadTest {

    @Test
    public void loadAsync_readsOnBackgroundAndRendersOnlyAfterMainPost() throws Exception {
        ExecutorService background = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-diagnostics-bg");
            thread.setDaemon(true);
            return thread;
        });
        try {
            final List<Runnable> mainQueue = new ArrayList<>();
            final AtomicReference<String> readerThread = new AtomicReference<>();
            final CountDownLatch read = new CountDownLatch(1);
            final CountDownLatch mainPosted = new CountDownLatch(1);
            final AtomicBoolean rendered = new AtomicBoolean(false);
            final String testThread = Thread.currentThread().getName();

            boolean submitted = DiagnosticsFragment.loadAsync(
                    background,
                    runnable -> {
                        mainQueue.add(runnable);
                        mainPosted.countDown();
                    },
                    () -> {
                        readerThread.set(Thread.currentThread().getName());
                        read.countDown();
                        return "payload";
                    },
                    value -> {
                        assertEquals("payload", value);
                        rendered.set(true);
                    },
                    new AtomicInteger());

            assertTrue("后台执行器可用时必须提交", submitted);
            assertTrue("读取未执行", read.await(5, TimeUnit.SECONDS));
            assertTrue("读取完成后必须投递主线程渲染", mainPosted.await(5, TimeUnit.SECONDS));
            assertEquals("读取必须在后台线程，不能占用主线程", "test-diagnostics-bg",
                    readerThread.get());
            assertFalse("读取完成前不能渲染", rendered.get());
            assertFalse("读取线程名不应等于调用线程", testThread.equals(readerThread.get()));

            assertEquals("渲染必须经主线程投递", 1, mainQueue.size());
            mainQueue.get(0).run();
            assertTrue(rendered.get());
        } finally {
            background.shutdownNow();
        }
    }

    @Test
    public void loadAsync_dropsSupersededResult() {
        Executor direct = Runnable::run;
        List<Runnable> mainQueue = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        AtomicInteger generation = new AtomicInteger();

        DiagnosticsFragment.loadAsync(direct, mainQueue::add,
                () -> "old", rendered::add, generation);
        DiagnosticsFragment.loadAsync(direct, mainQueue::add,
                () -> "new", rendered::add, generation);

        assertEquals(2, mainQueue.size());
        mainQueue.get(0).run();
        assertTrue("旧快照（即使晚到）必须被丢弃", rendered.isEmpty());
        mainQueue.get(1).run();
        assertEquals("只有最新一次刷新能渲染", 1, rendered.size());
        assertEquals("new", rendered.get(0));
    }

    @Test
    public void loadAsync_rejectedExecutorReportsFailureAndSkipsRead() {
        Executor rejected = task -> {
            throw new RejectedExecutionException("executor closed");
        };
        AtomicBoolean read = new AtomicBoolean(false);

        boolean submitted = DiagnosticsFragment.loadAsync(
                rejected, Runnable::run, () -> {
                    read.set(true);
                    return null;
                }, value -> {
                }, new AtomicInteger());

        assertFalse("执行器已关闭时必须返回 false", submitted);
        assertFalse("没有提交就不能读取", read.get());
    }

    // ------------------------------------------------------------ 导出诊断包文本段

    @Test
    public void crashesSection_hiddenWhenEmptyAndNewestFirst() throws Exception {
        assertEquals("无快照时不显示崩溃段", "",
                DiagnosticsFragment.crashesSection(null));
        assertEquals("无崩溃时不显示崩溃段",
                "", DiagnosticsFragment.crashesSection(new JSONObject()));

        JSONObject state = new JSONObject().put("crashes", new JSONArray()
                .put("2026-11-01 12:00:00 main java.lang.NullPointerException at a.b.c")
                .put("2026-11-01 11:00:00 main java.lang.Error at x.y.z"));
        String section = DiagnosticsFragment.crashesSection(state);
        assertTrue("必须包含段标题", section.startsWith("最近崩溃"));
        assertTrue("包含最新一条内容", section.contains("java.lang.NullPointerException"));
        assertTrue("最新一条在前（crashes[0] 为最新）",
                section.indexOf("12:00:00") < section.indexOf("11:00:00"));
    }

    @Test
    public void recentHitsSection_takesMostRecentItemsOnly() throws Exception {
        assertEquals("无观测时不显示命中段",
                "", DiagnosticsFragment.recentHitsSection(null));
        assertEquals("空 items 时不显示命中段",
                "", DiagnosticsFragment.recentHitsSection(new JSONObject()));

        JSONArray items = new JSONArray();
        for (int i = 0; i < 30; i++) {
            items.put(new JSONObject().put("key", "k" + i)
                    .put("group", "c").put("count", i).put("lastAt", i));
        }
        String section = DiagnosticsFragment.recentHitsSection(
                new JSONObject().put("items", items));
        assertTrue("必须包含段标题", section.startsWith("最近命中"));
        assertTrue("包含最近一次观测", section.contains("  k29 c ×29\n"));
        assertFalse("最多 20 条：lastAt 更旧的 k9 不出现", section.contains("  k9 c ×9\n"));
        assertFalse(section.contains("  k0 c ×0\n"));
        assertTrue("按 lastAt 降序", section.indexOf("k29") < section.indexOf("k28"));
        assertEquals("标题行 + 20 条", 21,
                section.split("\n", -1).length - 1);
    }

    @Test
    public void buildExportPackage_containsAllKeySectionsInOrder() throws Exception {
        JSONObject state = new JSONObject().put("crashes", new JSONArray()
                .put("2026-11-01 12:00:00 main java.lang.Boom at A.a(A.java:1)"));
        JSONObject experiments = new JSONObject().put("items", new JSONArray()
                .put(new JSONObject().put("key", "ad_01_splash").put("group", "c")
                        .put("count", 3).put("lastAt", 1L)));

        String text = DiagnosticsFragment.buildExportPackage("2.4.7", "Android 15 (API 35)",
                "102", "9.6.13", "AD-01 启动页广告 registered · hit 3\n",
                state, experiments);

        assertTrue("模块版本段", text.contains("模块版本: 2.4.7"));
        assertTrue("系统版本段", text.contains("系统版本: Android 15 (API 35)"));
        assertTrue("框架 API 段", text.contains("框架 API: 102"));
        assertTrue("目标版本段", text.contains("目标版本: 9.6.13"));
        assertTrue("各功能状态段（调用方拼入）", text.contains("AD-01 启动页广告"));
        assertTrue("最近命中段", text.contains("最近命中:") && text.contains("ad_01_splash"));
        assertTrue("最近崩溃段", text.contains("最近崩溃:")
                && text.contains("java.lang.Boom at A.a(A.java:1)"));
        assertTrue("段顺序：模块版本 → 功能状态 → 最近命中 → 最近崩溃",
                text.indexOf("模块版本") < text.indexOf("AD-01")
                        && text.indexOf("AD-01") < text.indexOf("最近命中")
                        && text.indexOf("最近命中") < text.indexOf("最近崩溃"));
    }

    @Test
    public void buildExportPackage_nullInputsDegradeToDash() {
        String text = DiagnosticsFragment.buildExportPackage(
                null, "", null, "", null, null, null);
        assertTrue("缺失版本降级为 —", text.contains("模块版本: —"));
        assertTrue(text.contains("系统版本: —"));
        assertTrue(text.contains("框架 API: —"));
        assertTrue(text.contains("目标版本: —"));
        assertFalse("无崩溃/无观测时不输出对应段",
                text.contains("最近命中:") || text.contains("最近崩溃:"));
    }
}
