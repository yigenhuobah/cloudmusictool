package com.cloudmusictool.app;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

/**
 * context 未就绪时的有界重试调度（冷启动首报丢失修复）。
 *
 * 生产路径：publish/flush -> sExecutor.execute(doPublish(0))；context 为 null 时
 * 按 1s/2s/4s 最多重试 3 次，同一通道只保留一条重试链。本测试用即时执行 +
 * 记录 schedule 的执行器，不启动真线程；Context 无法在纯 JVM 单测中轻量伪造，
 * 因此只覆盖调度决策，不覆盖 provider 投递。
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

    private static FakeChannel channel(String json) {
        return new FakeChannel("com.example.module", json);
    }

    private static final class FakeChannel implements TargetProcessPublisher.Channel {
        private final String mPkg;
        private final String mJson;

        FakeChannel(String pkg, String json) {
            mPkg = pkg;
            mJson = json;
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
            return mJson;
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
     * 不启动真实线程。
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
            scheduledTasks.add(new ScheduledTask(command, unit.toMillis(delay)));
            return null;
        }

        void runScheduled(int index) {
            scheduledTasks.get(index).task.run();
        }

        static final class ScheduledTask {
            final Runnable task;
            final long delayMs;

            ScheduledTask(Runnable task, long delayMs) {
                this.task = task;
                this.delayMs = delayMs;
            }
        }
    }
}
