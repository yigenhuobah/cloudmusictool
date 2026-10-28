package com.cloudmusictool.app;

import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * context 未就绪时的有界重试调度（冷启动首报丢失修复）。
 *
 * 生产路径：publish/flush -> sExecutor.execute(doPublish(0))；context 为 null 时
 * 按 1s/2s/4s 最多重试 3 次，同一通道只保留一条重试链。本测试用即时执行 +
 * 记录 schedule 的执行器，不启动真线程。
 *
 * 重试链在途任务以 ScheduledFuture 保存：成功/短路/耗尽时 cancel(false)，
 * 因此假执行器必须返回可取消的 future（生产代码会消费返回值），并像真实
 * 执行器一样在运行前跳过已取消任务。成功路径用 {@link FakeContext} 触发：
 * mockable android.jar 的 ContentResolver.call 是桩方法（returnDefaultValues），
 * 返回 null 即视为投递成功，不需要真实 provider。
 */
public class TargetProcessPublisherRetryTest {

    private RecordingExecutor mExecutor;
    private ScheduledExecutorService mOrigExecutor;
    private android.content.Context mOrigContext;

    @Before
    public void setUp() {
        mExecutor = new RecordingExecutor();
        mOrigExecutor = TargetProcessPublisher.sExecutor;
        mOrigContext = TargetProcessPublisher.sTargetContext;
        TargetProcessPublisher.sExecutor = mExecutor;
        TargetProcessPublisher.sTargetContext = null;
        TargetProcessPublisher.resetRetryStateForTest();
    }

    @After
    public void tearDown() {
        TargetProcessPublisher.sExecutor = mOrigExecutor;
        TargetProcessPublisher.sTargetContext = mOrigContext;
        TargetProcessPublisher.resetRetryStateForTest();
        mExecutor.shutdownNow();
    }

    @Test
    public void contextUnavailable_schedulesFirstRetryAfterOneSecond() {
        TargetProcessPublisher.flush(channel("{}"));
        assertEquals(1, mExecutor.scheduledTasks.size());
        assertEquals(1000L, mExecutor.scheduledTasks.get(0).delayMs);
    }

    @Test
    public void retriesBackOffAndStopAfterThreeAttempts() {
        TargetProcessPublisher.flush(channel("{}"));
        assertEquals(1, mExecutor.scheduledTasks.size());

        mExecutor.runScheduled(0);
        assertEquals(2, mExecutor.scheduledTasks.size());
        assertEquals(2000L, mExecutor.scheduledTasks.get(1).delayMs);

        mExecutor.runScheduled(1);
        assertEquals(3, mExecutor.scheduledTasks.size());
        assertEquals(4000L, mExecutor.scheduledTasks.get(2).delayMs);

        mExecutor.runScheduled(2);
        assertEquals("三次重试后停止", 3, mExecutor.scheduledTasks.size());
    }

    @Test
    public void duplicatePublishesShareOneRetryChain() {
        FakeChannel channel = channel("{}");
        TargetProcessPublisher.flush(channel);
        TargetProcessPublisher.flush(channel);
        TargetProcessPublisher.flush(channel);
        assertEquals(3, mExecutor.directTasks);
        assertEquals("同一通道只排一条重试链", 1, mExecutor.scheduledTasks.size());
    }

    @Test
    public void differentChannelsHaveIndependentChains() {
        TargetProcessPublisher.flush(channel("{}"));
        TargetProcessPublisher.flush(channel("{\"x\":1}"));
        assertEquals(2, mExecutor.scheduledTasks.size());
    }

    @Test
    public void missingModulePackage_doesNotScheduleRetry() {
        TargetProcessPublisher.flush(new FakeChannel("", "{}"));
        assertEquals(0, mExecutor.scheduledTasks.size());
    }

    @Test
    public void nullJson_doesNotScheduleRetry() {
        TargetProcessPublisher.flush(new FakeChannel("com.example.module", null));
        assertEquals(0, mExecutor.scheduledTasks.size());
    }

    /**
     * 成功发布后取消在途重试：此前只清 sRetryPending 标记，已排的重试任务仍会执行
     * 并重复发送；现在必须 cancel(false)，且迟到的重试不再构建/发送快照。
     */
    @Test
    public void successfulPublish_cancelsInFlightRetry_noDuplicateSend() {
        FakeChannel channel = channel("{}");
        TargetProcessPublisher.flush(channel);
        assertEquals(1, mExecutor.scheduledTasks.size());
        FakeScheduledFuture retry = mExecutor.scheduledTasks.get(0).future;
        assertFalse(retry.isCancelled());
        assertEquals("首次发布已构建一次快照", 1, channel.buildCalls);

        // context 就绪：本次发布走成功路径，finally 中取消在途重试。
        TargetProcessPublisher.sTargetContext = new FakeContext();
        // 先确认桩 resolver 的 call 正常返回（而不是抛异常落入失败分支）。
        assertNull(new FakeContext().getContentResolver()
                .call("authority", "report", null, new Bundle()));
        TargetProcessPublisher.flush(channel);
        assertTrue("成功发布后必须取消在途重试", retry.isCancelled());
        assertEquals("成功发布构建一次快照", 2, channel.buildCalls);

        // 真实执行器不会启动已取消任务：迟到重试既不重复发送，也不再排新链。
        mExecutor.runScheduled(0);
        assertEquals("已取消的重试不再构建/发送快照", 2, channel.buildCalls);
        assertEquals(1, mExecutor.scheduledTasks.size());
    }

    /** 数据被清空（json == null）短路时同样取消在途重试。 */
    @Test
    public void publishBecomesEmpty_cancelsInFlightRetry() {
        FakeChannel channel = channel("{}");
        TargetProcessPublisher.flush(channel);
        FakeScheduledFuture retry = mExecutor.scheduledTasks.get(0).future;

        channel.json = null;
        TargetProcessPublisher.flush(channel);
        assertTrue("短路路径也必须取消在途重试", retry.isCancelled());

        mExecutor.runScheduled(0);
        assertEquals("已取消的重试不再排新链", 1, mExecutor.scheduledTasks.size());
    }

    private static FakeChannel channel(String json) {
        return new FakeChannel("com.example.module", json);
    }

    /**
     * 纯 JVM 无法构造真实 Context：ContextWrapper 只覆写 getContentResolver，
     * resolver 的 call 走 mockable android.jar 的桩实现并返回 null，即成功路径。
     */
    private static final class FakeContext extends ContextWrapper {
        private final ContentResolver mResolver = new ContentResolver(null) {
        };

        FakeContext() {
            super(null);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }
    }

    private static final class FakeChannel implements TargetProcessPublisher.Channel {
        private final String mPkg;
        String json;
        int buildCalls;

        FakeChannel(String pkg, String json) {
            mPkg = pkg;
            this.json = json;
        }

        @Override
        public String modulePackage() {
            return mPkg;
        }

        @Override
        public String type() {
            return "mounts";
        }

        @Override
        public String buildJson() {
            buildCalls++;
            return json;
        }

        @Override
        public String contextUnavailableWarning() {
            return "context unavailable";
        }

        @Override
        public String publishFailureWarning(Throwable t) {
            return "publish failed: " + t;
        }
    }

    /**
     * 即时执行 execute（doPublish 同步跑），schedule 只记录延迟由用例手动运行；
     * 不启动真实线程。schedule 返回可取消的假 future（生产代码会保存并 cancel）。
     */
    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {
        final List<ScheduledTask> scheduledTasks = new ArrayList<>();
        int directTasks;

        RecordingExecutor() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            directTasks++;
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            FakeScheduledFuture future = new FakeScheduledFuture();
            scheduledTasks.add(new ScheduledTask(command, unit.toMillis(delay), future));
            return future;
        }

        /** 模拟真实执行器：已取消的任务不会启动。 */
        void runScheduled(int index) {
            ScheduledTask task = scheduledTasks.get(index);
            if (!task.future.isCancelled()) {
                task.task.run();
            }
        }

        static final class ScheduledTask {
            final Runnable task;
            final long delayMs;
            final FakeScheduledFuture future;

            ScheduledTask(Runnable task, long delayMs, FakeScheduledFuture future) {
                this.task = task;
                this.delayMs = delayMs;
                this.future = future;
            }
        }
    }

    /** 可取消的假 future：cancel(false) 后执行器不再启动对应任务。 */
    private static final class FakeScheduledFuture implements ScheduledFuture<Object> {
        private boolean mCancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (mCancelled) {
                return false;
            }
            mCancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return mCancelled;
        }

        @Override
        public boolean isDone() {
            return mCancelled;
        }

        @Override
        public Object get() {
            throw new CancellationException("fake scheduled task is not waitable");
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new CancellationException("fake scheduled task is not waitable");
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
