package com.cloudmusictool.app;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * HookState 领先 + 尾沿节流调度决策的纯 JVM 单测（评审 concurrency-4 的修复钉子）。
 *
 * 旧实现是领先沿节流：2 秒窗口内的状态变化被直接丢弃且窗口推进先于队列 CAS，
 * 最后一次变化可能永不上报。新实现把决策抽成 {@link TargetProcessPublisher.PublishThrottle}：
 * 窗口首个变化立即发送（领先沿），窗口内后续变化只置 pending，
 * 窗口关闭时必发一次尾沿（携带最新状态）；flush() 最终快照语义保留。
 *
 * 全部用例不启动真线程：时钟注入 {@link MutableClock}，执行器注入
 * {@link RecordingExecutor}（只记录任务、由用例手动运行），决策方法直接调用。
 * Feature 各用例互不相同，避免与 HookStateJsonContractTest 的静态状态串扰。
 */
public class HookStatePublishScheduleTest {

    private static final String MODULE_PKG = "com.example.module";

    private MutableClock mClock;
    private RecordingExecutor mExecutor;
    private ScheduledExecutorService mOrigExecutor;
    private TargetProcessPublisher.PublishThrottle mOrigThrottle;

    @Before
    public void setUp() {
        mClock = new MutableClock();
        mExecutor = new RecordingExecutor();
        mOrigExecutor = TargetProcessPublisher.sExecutor;
        mOrigThrottle = HookState.sThrottle;
        TargetProcessPublisher.sExecutor = mExecutor;
        HookState.sThrottle = new TargetProcessPublisher.PublishThrottle(mClock);
        HookState.init(MODULE_PKG, "main");
    }

    @After
    public void tearDown() {
        TargetProcessPublisher.sExecutor = mOrigExecutor;
        HookState.sThrottle = mOrigThrottle;
    }

    /** 窗口内两次变化 -> 恰一次领先沿 + 一次尾沿，尾沿只在窗口关闭时发。 */
    @Test
    public void twoChangesInWindow_exactlyOneLeadingAndOneTrailing() {
        TargetProcessPublisher.PublishThrottle throttle = new TargetProcessPublisher.PublishThrottle(mClock);
        mClock.set(0L);
        TargetProcessPublisher.PublishThrottle.Decision first = throttle.decide();
        assertTrue("窗口首个变化应立即发送（领先沿）", first.sendNow);
        assertFalse("领先沿无需再排尾沿", first.hasTrailing());

        mClock.advance(500L);
        TargetProcessPublisher.PublishThrottle.Decision second = throttle.decide();
        assertFalse("窗口内后续变化不应立即发送", second.sendNow);
        assertTrue("窗口内变化应合并为尾沿", second.hasTrailing());
        assertEquals("尾沿应排在窗口关闭时刻（领先沿后 2 秒）",
                1500L, second.trailingDelayMs);

        mClock.advance(1500L); // t=2000 窗口关闭
        assertTrue("窗口关闭时必发一次尾沿", throttle.onWindowClose());
        assertFalse("尾沿只发一次，无多余发送", throttle.onWindowClose());
    }

    /** 窗口内第三次变化不重复排尾沿、不触发新的领先沿。 */
    @Test
    public void thirdChangeInWindow_noDuplicateTrailing() {
        TargetProcessPublisher.PublishThrottle throttle = new TargetProcessPublisher.PublishThrottle(mClock);
        mClock.set(0L);
        assertTrue(throttle.decide().sendNow);

        mClock.advance(500L);
        assertTrue(throttle.decide().hasTrailing());

        mClock.advance(300L); // t=800 第三次变化
        TargetProcessPublisher.PublishThrottle.Decision third = throttle.decide();
        assertFalse(third.sendNow);
        assertFalse("尾沿检查任务只排队一次", third.hasTrailing());

        mClock.advance(1200L); // t=2000
        assertTrue(throttle.onWindowClose());
        assertFalse(throttle.onWindowClose());
    }

    /** 领先沿后 2 秒无事件 -> 无多余发送；窗口过期后的新变化重新走领先沿而非丢弃。 */
    @Test
    public void noEventAfterLeading_noExtraSends() {
        TargetProcessPublisher.PublishThrottle throttle = new TargetProcessPublisher.PublishThrottle(mClock);
        mClock.set(0L);
        TargetProcessPublisher.PublishThrottle.Decision first = throttle.decide();
        assertTrue(first.sendNow);
        assertFalse("领先沿后无事件：不应排尾沿", first.hasTrailing());

        mClock.advance(2000L);
        assertFalse("无 pending：窗口关闭不发送", throttle.onWindowClose());

        mClock.advance(1000L); // t=3000 新变化
        TargetProcessPublisher.PublishThrottle.Decision next = throttle.decide();
        assertTrue("窗口过期后的变化应开新窗立即发送", next.sendNow);
        assertFalse(next.hasTrailing());
    }

    /**
     * 尾沿携带最新状态：窗口内最后一次变化不丢弃，尾沿任务发送时才取快照，
     * 单线程工作线程保证读到的就是最新状态；尾沿后窗口关闭，新变化重新走领先沿。
     */
    @Test
    public void trailingCarriesLatestState_afterLastChangeInWindow() throws Exception {
        mClock.set(0L);
        HookState.enable(Feature.HOME_BLOCKS);
        assertEquals("窗口首个变化：恰一次领先沿", 1, mExecutor.directTasks.size());
        assertEquals(0, mExecutor.scheduledTasks.size());

        mClock.advance(500L);
        HookState.installed(Feature.HOME_BLOCKS, "m1");
        assertEquals("窗口内变化不重复领先沿", 1, mExecutor.directTasks.size());
        assertEquals("恰排一次尾沿", 1, mExecutor.scheduledTasks.size());
        assertEquals("尾沿排在窗口关闭时刻", 1500L, mExecutor.scheduledTasks.get(0).delayMs);

        mClock.advance(400L); // t=900 窗口内最后一次变化
        HookState.installed(Feature.HOME_BLOCKS, "m2");
        assertEquals(1, mExecutor.directTasks.size());
        assertEquals("仍只有一次尾沿", 1, mExecutor.scheduledTasks.size());

        // doPublish 发送时才调 buildJson 取快照；此刻快照已包含最新状态。
        JSONObject o = new JSONObject(buildJson())
                .getJSONObject("features").getJSONObject(Feature.HOME_BLOCKS.name());
        assertEquals("尾沿快照携带最新状态", 2, o.getInt("installed"));

        mClock.advance(1100L); // t=2000 窗口关闭，手动运行尾沿任务
        mExecutor.runScheduled(0);

        mClock.advance(100L); // t=2100 尾沿后的新变化
        HookState.enable(Feature.HOME_BLOCKS);
        assertEquals("尾沿后窗口已关，新变化重新走领先沿（最后一次变化不丢）",
                2, mExecutor.directTasks.size());
    }

    /** flush 最终快照语义保留：不受节流限制立即提交，也不改变节流状态。 */
    @Test
    public void flush_bypassesThrottle() {
        mClock.set(0L);
        HookState.enable(Feature.BOTTOM_NAV);
        assertEquals(1, mExecutor.directTasks.size());

        mClock.advance(500L);
        HookState.note(Feature.BOTTOM_NAV, "findMethod failed: x"); // 窗口内变化：置 pending
        assertEquals("节流中的变化不立即发送", 1, mExecutor.directTasks.size());
        assertEquals(1, mExecutor.scheduledTasks.size());

        HookState.flush();
        assertEquals("flush 绕过节流，立即提交", 2, mExecutor.directTasks.size());
        assertEquals("flush 不动节流状态", 1, mExecutor.scheduledTasks.size());

        mClock.advance(1500L); // t=2000 尾沿照常
        mExecutor.runScheduled(0);

        HookState.flush();
        assertEquals("再次 flush 仍立即提交", 3, mExecutor.directTasks.size());
    }

    /**
     * 并发 smoke：多线程同时 decide/onWindowClose（假时钟固定在窗口内），锁保证
     * 「关窗 + 消费 pending」原子。精确交错需要在方法内部插桩，纯 JVM 无法确定性
     * 复现，因此钉住设计不变量：不抛异常、领先沿/尾沿次数不超过关窗次数 + 1、
     * 关窗后下一次变化必走领先沿。
     */
    @Test
    public void concurrentDecideAndClose_keepsWindowInvariants() throws Exception {
        final TargetProcessPublisher.PublishThrottle throttle =
                new TargetProcessPublisher.PublishThrottle(mClock);
        mClock.set(0L);
        final int threads = 8;
        final int iterations = 500;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger leading = new AtomicInteger();
        final AtomicInteger trailing = new AtomicInteger();
        final List<Future<?>> workers = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        try {
            for (int i = 0; i < threads; i++) {
                workers.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        start.await();
                        for (int j = 0; j < iterations; j++) {
                            TargetProcessPublisher.PublishThrottle.Decision d = throttle.decide();
                            if (d.sendNow) {
                                leading.incrementAndGet();
                            }
                            if (d.hasTrailing()) {
                                trailing.incrementAndGet();
                                assertTrue("尾沿延迟必须为正", d.trailingDelayMs > 0);
                            }
                        }
                        return null;
                    }
                }));
            }
            Future<Integer> closer = pool.submit(new Callable<Integer>() {
                @Override
                public Integer call() throws Exception {
                    start.await();
                    int closes = 0;
                    for (int j = 0; j < iterations; j++) {
                        // 每次调用都关窗（无论是否有 pending），都可能放行一次新的领先沿。
                        throttle.onWindowClose();
                        closes++;
                    }
                    return closes;
                }
            });
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(10, TimeUnit.SECONDS);
            }
            int closes = closer.get(10, TimeUnit.SECONDS);

            // 固定时钟下窗口不会自然过期：每次关窗最多再允许一次领先沿/一次尾沿排队。
            assertTrue("领先沿次数不超过关窗次数 + 1", leading.get() <= closes + 1);
            assertTrue("尾沿检查任务每次关窗最多再排一次", trailing.get() <= closes + 1);
        } finally {
            pool.shutdownNow();
        }

        // 最后一次关窗后窗口必为关闭态：下一次变化重新走领先沿（pending 不会泄漏）。
        throttle.onWindowClose();
        assertTrue("关窗后的首次变化必须立即发送", throttle.decide().sendNow);
    }

    private static String buildJson() throws Exception {
        Method m = HookState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    /** 假时钟：测试手动推进，不依赖真实时间。 */
    private static final class MutableClock implements TargetProcessPublisher.PublishThrottle.Clock {
        private long mNow;

        @Override
        public long now() {
            return mNow;
        }

        void set(long now) {
            mNow = now;
        }

        void advance(long ms) {
            mNow += ms;
        }
    }

    /**
     * 可数执行器：只记录任务、由用例手动运行，不启动线程。
     * 生产代码只用到 execute / schedule 两个入口，其余入口一律快速失败。
     */
    private static final class RecordingExecutor implements ScheduledExecutorService {
        final List<Runnable> directTasks = new ArrayList<>();
        final List<ScheduledTask> scheduledTasks = new ArrayList<>();

        static final class ScheduledTask {
            final Runnable task;
            final long delayMs;

            ScheduledTask(Runnable task, long delayMs) {
                this.task = task;
                this.delayMs = delayMs;
            }
        }

        void runScheduled(int index) {
            scheduledTasks.get(index).task.run();
        }

        @Override
        public void execute(Runnable command) {
            directTasks.add(command);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduledTasks.add(new ScheduledTask(command, unit.toMillis(delay)));
            // 生产代码会保存 schedule 返回值以便成功路径取消，必须返回非 null 的假 future。
            return new FakeScheduledFuture();
        }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("单测只用到 execute/schedule");
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw unsupported();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                      long period, TimeUnit unit) {
            throw unsupported();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                         long delay, TimeUnit unit) {
            throw unsupported();
        }

        @Override
        public void shutdown() {
            throw unsupported();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw unsupported();
        }

        @Override
        public boolean isShutdown() {
            throw unsupported();
        }

        @Override
        public boolean isTerminated() {
            throw unsupported();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            throw unsupported();
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw unsupported();
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            throw unsupported();
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw unsupported();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw unsupported();
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                             long timeout, TimeUnit unit) {
            throw unsupported();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw unsupported();
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                               long timeout, TimeUnit unit) {
            throw unsupported();
        }
    }

    /** schedule 返回值的最小假实现：本测试不等待、不取消，只要求非 null。 */
    private static final class FakeScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException("单测不等待 future");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("单测不等待 future");
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
