package com.cloudmusictool.app;

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
}
