package com.cloudmusictool.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 崩溃接线（ALL-CRASH-01）的生产端钉子：
 *
 * <ul>
 *   <li>{@code installCrashGuard} 必须把 {@link CloudMusicToolModule.CrashRecordingHandler}
 *       装到 default UEH 上且只装一次；</li>
 *   <li>handler 先 {@code HookState.recordCrash}，再经 CrashFlush 同步 flush，
 *       previous 仍按原顺序被转交；</li>
 *   <li>同步发送边界必须携带 sync=true（即 extras 的 KEY_SYNC=true），且快照里能看到
 *       刚落账的崩溃；真正落盘结果由 {@link StatusProviderSyncReportTest} 覆盖。</li>
 * </ul>
 *
 * <p>Bundle 在 JVM 单测里是 final 的不可读空桩，因此用
 * {@link TargetProcessPublisher.Transport} seam 捕获 {@link TargetProcessPublisher.Report}，
 * 并用 {@link TargetProcessPublisher#extrasValues} 验证 KEY_SYNC 映射。
 */
public class CloudMusicToolModuleCrashGuardTest {

    private static final String MODULE = "com.example.module";

    private Thread.UncaughtExceptionHandler mOrigHandler;
    private android.content.Context mOrigContext;
    private ScheduledExecutorService mOrigExecutor;
    private TargetProcessPublisher.Transport mOrigTransport;
    private DiscardExecutor mExecutor;
    private RecordingTransport mTransport;

    @Before
    public void setUp() {
        mOrigHandler = Thread.getDefaultUncaughtExceptionHandler();
        mOrigContext = TargetProcessPublisher.sTargetContext;
        mOrigExecutor = TargetProcessPublisher.sExecutor;
        mOrigTransport = TargetProcessPublisher.sTransport;

        mExecutor = new DiscardExecutor();
        mTransport = new RecordingTransport(true);
        TargetProcessPublisher.sExecutor = mExecutor;
        TargetProcessPublisher.sTransport = mTransport;
        TargetProcessPublisher.sTargetContext = new ContextWrapper(null);
        TargetProcessPublisher.resetRetryStateForTest();
        HookState.init(MODULE, "main");
        CloudMusicToolModule.resetCrashGuardForTest();
    }

    @After
    public void tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(mOrigHandler);
        TargetProcessPublisher.sTargetContext = mOrigContext;
        TargetProcessPublisher.sExecutor = mOrigExecutor;
        TargetProcessPublisher.sTransport = mOrigTransport;
        TargetProcessPublisher.resetRetryStateForTest();
        CloudMusicToolModule.resetCrashGuardForTest();
        mExecutor.shutdownNow();
    }

    @Test
    public void installCrashGuardWrapsDefaultHandlerExactlyOnce() {
        RecordingPrevious previous = new RecordingPrevious();
        Thread.setDefaultUncaughtExceptionHandler(previous);

        CloudMusicToolModule.installCrashGuard(new CloudMusicToolModule());

        Thread.UncaughtExceptionHandler installed =
                Thread.getDefaultUncaughtExceptionHandler();
        assertNotNull(installed);
        assertTrue("必须安装 CrashRecordingHandler",
                installed instanceof CloudMusicToolModule.CrashRecordingHandler);
        assertNotSame(previous, installed);

        CloudMusicToolModule.installCrashGuard(new CloudMusicToolModule());
        assertSame("幂等位必须阻止重复包装", installed,
                Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test
    public void installedHandlerRecordsCrashThenFlushesSyncAndDelegates() {
        RecordingPrevious previous = new RecordingPrevious();
        Thread.setDefaultUncaughtExceptionHandler(previous);
        CloudMusicToolModule.installCrashGuard(new CloudMusicToolModule());

        Throwable boom = new IllegalStateException("crash-wiring");
        Thread thread = new Thread("crash-test-thread");
        Thread.getDefaultUncaughtExceptionHandler().uncaughtException(thread, boom);

        assertEquals("previous 必须被转交", 1, previous.mCalls);
        assertSame(boom, previous.mThrowable);

        assertEquals("崩溃路径必须触发一次同步回传", 1, mTransport.calls);
        assertEquals("必须发送到模块 App 的 provider", MODULE, mTransport.modulePackage);
        assertTrue("崩溃回传必须要求 sync commit", mTransport.report.sync);
        assertEquals("extras 必须携带 KEY_SYNC=true", Boolean.TRUE,
                TargetProcessPublisher.extrasValues(mTransport.report)
                        .get(StatusProvider.KEY_SYNC));

        String json = mTransport.report.json;
        assertNotNull(json);
        assertTrue("recordCrash 的结果必须已进入本次同步快照",
                json.contains("\"crashes\""));
        assertTrue("快照必须包含异常类名", json.contains(IllegalStateException.class.getName()));
        assertTrue("快照必须包含崩溃线程名", json.contains("crash-test-thread"));
    }

    @Test
    public void handlerDelegatesToPreviousEvenWhenFlushTargetRejects() {
        // provider 拒绝落盘时 flushNow 返回 false，但 handler 只做尽力回传，
        // previous 仍必须收到原异常（宿主原有行为不变）。
        RecordingTransport rejecting = new RecordingTransport(false);
        TargetProcessPublisher.sTransport = rejecting;
        RecordingPrevious previous = new RecordingPrevious();

        new CloudMusicToolModule.CrashRecordingHandler(previous)
                .uncaughtException(new Thread("rejecting-flush"), new RuntimeException("boom"));

        assertEquals(1, rejecting.calls);
        assertTrue("接收方拒绝也不改变 sync 请求标记", rejecting.report.sync);
        assertEquals("previous 仍必须被调用", 1, previous.mCalls);
    }

    /** 丢弃所有异步任务：崩溃用例只允许 CrashFlush 那一次同步回传进入断言。 */
    private static final class DiscardExecutor extends ScheduledThreadPoolExecutor {
        DiscardExecutor() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            // 普通节流回传不执行，避免与崩溃同步 flush 的断言竞争。
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return null;
        }
    }

    /** 记录发送边界的 Transport；acknowledge=false 模拟 StatusProvider 拒绝/commit 失败。 */
    private static final class RecordingTransport implements TargetProcessPublisher.Transport {
        private final boolean mAcknowledge;
        int calls;
        String modulePackage;
        TargetProcessPublisher.Report report;

        RecordingTransport(boolean acknowledge) {
            mAcknowledge = acknowledge;
        }

        @Override
        public Bundle send(Context ctx, String modulePackage,
                           TargetProcessPublisher.Report report) {
            calls++;
            this.modulePackage = modulePackage;
            this.report = report;
            return mAcknowledge ? new Bundle() : null;
        }
    }

    private static final class RecordingPrevious
            implements Thread.UncaughtExceptionHandler {
        int mCalls;
        Throwable mThrowable;

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            mCalls++;
            mThrowable = throwable;
        }
    }
}
