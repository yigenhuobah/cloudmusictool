package com.cloudmusictool.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 崩溃路径同步 flush 的契约（ALL-CRASH-01）：
 * {@link TargetProcessPublisher#flushNow} 必须在调用线程内直接完成发送，
 * 不排队到共享执行器、不安排 context 重试；并用 sync 标记要求 StatusProvider commit 落盘。
 * 返回值必须反映模块 App 的落盘确认：StatusProvider 在 sync commit 成功时返回非 null
 * Bundle，commit 失败/报告被拒绝时返回 null，flushNow 据此返回 false。
 * {@link HookState#flushNow()} 只是把 mounts 通道接上该入口，且自身绝不抛异常。
 *
 * <p>Bundle 在 JVM 单测里是 final 的不可读空桩，因此发送边界通过
 * {@link TargetProcessPublisher.Transport} seam 捕获 {@link TargetProcessPublisher.Report}
 * （type/json/sync），并用 {@link TargetProcessPublisher#extrasValues} 钉住
 * KEY_SYNC 的 extras 映射。
 */
public class TargetProcessPublisherFlushNowTest {

    private static final String MODULE = "com.example.module";

    private android.content.Context mOrigContext;
    private ScheduledExecutorService mOrigExecutor;
    private TargetProcessPublisher.Transport mOrigTransport;
    private NoSchedulingExecutor mExecutor;

    @Before
    public void setUp() {
        mOrigContext = TargetProcessPublisher.sTargetContext;
        mOrigExecutor = TargetProcessPublisher.sExecutor;
        mOrigTransport = TargetProcessPublisher.sTransport;
        mExecutor = new NoSchedulingExecutor();
        TargetProcessPublisher.sTransport = null;
        TargetProcessPublisher.sTargetContext = new FakeContext(new ContentResolver(null) {
        });
        TargetProcessPublisher.sExecutor = mExecutor;
        TargetProcessPublisher.resetRetryStateForTest();
    }

    @After
    public void tearDown() {
        TargetProcessPublisher.sTargetContext = mOrigContext;
        TargetProcessPublisher.sExecutor = mOrigExecutor;
        TargetProcessPublisher.sTransport = mOrigTransport;
        TargetProcessPublisher.resetRetryStateForTest();
        mExecutor.shutdownNow();
    }

    @Test
    public void flushNowDeliversInlineWithSyncFlagAndReturnsProviderAck() {
        RecordingTransport transport = new RecordingTransport(true);
        TargetProcessPublisher.sTransport = transport;
        FakeChannel channel = new FakeChannel(MODULE, "{\"ok\":1}");

        // sExecutor 被替换成任何 execute/schedule 都抛 AssertionError 的实现：
        // 调用成功即证明 flushNow 完全在调用线程完成。
        assertTrue(TargetProcessPublisher.flushNow(channel));

        assertEquals("必须在调用线程内直接发送", 1, transport.calls);
        assertEquals("必须发送到模块 App 的 provider", MODULE, transport.modulePackage);
        assertTrue("崩溃路径必须要求 sync commit", transport.report.sync);
        assertNull("mounts 通道不写 type 键", transport.report.type);
        assertEquals("{\"ok\":1}", transport.report.json);
        assertEquals("extras 必须携带 KEY_SYNC=true", Boolean.TRUE,
                TargetProcessPublisher.extrasValues(transport.report)
                        .get(StatusProvider.KEY_SYNC));
    }

    @Test
    public void flushNowReturnsFalseWhenProviderReturnsNull() {
        // 空 resolver 的 call 是桩方法（returnDefaultValues），返回 null：
        // 等价于 StatusProvider 没有真正落盘，flushNow 不能报成功。
        assertFalse(TargetProcessPublisher.flushNow(new FakeChannel(MODULE, "{}")));
    }

    @Test
    public void flushNowReturnsFalseWhenSyncCommitIsRejected() {
        // StatusProvider sync 路径 commit=false 时返回 null；flushNow 必须跟随为 false。
        RecordingTransport transport = new RecordingTransport(false);
        TargetProcessPublisher.sTransport = transport;

        assertFalse(TargetProcessPublisher.flushNow(new FakeChannel(MODULE, "{}")));

        assertEquals("拒绝前必须已经尝试发送一次", 1, transport.calls);
        assertTrue("sync 标记仍应随请求发出", transport.report.sync);
    }

    @Test
    public void extrasValuesMarksSyncOnlyOnCrashPath() {
        Map<String, Object> sync = TargetProcessPublisher.extrasValues(
                new TargetProcessPublisher.Report(null, "{\"a\":1}", true));
        assertEquals(Boolean.TRUE, sync.get(StatusProvider.KEY_SYNC));
        assertEquals("{\"a\":1}", sync.get(StatusProvider.KEY_JSON));
        assertFalse("type 为 null 时不写该键", sync.containsKey(StatusProvider.KEY_TYPE));

        Map<String, Object> async = TargetProcessPublisher.extrasValues(
                new TargetProcessPublisher.Report(null, "{\"a\":1}", false));
        assertFalse("普通回传不得携带 sync 标记",
                async.containsKey(StatusProvider.KEY_SYNC));

        Map<String, Object> experiments = TargetProcessPublisher.extrasValues(
                new TargetProcessPublisher.Report(StatusProvider.TYPE_EXPERIMENTS, "{}", false));
        assertEquals(StatusProvider.TYPE_EXPERIMENTS,
                experiments.get(StatusProvider.KEY_TYPE));
        assertFalse(experiments.containsKey(StatusProvider.KEY_SYNC));
    }

    @Test
    public void flushNowShortCircuitsWithoutPackageOrPayload() {
        assertFalse(TargetProcessPublisher.flushNow(new FakeChannel("", "{}")));
        assertFalse(TargetProcessPublisher.flushNow(new FakeChannel("pkg", null)));
        assertFalse(TargetProcessPublisher.flushNow(null));
    }

    @Test
    public void flushNowSwallowsBuildAndSendFailures() {
        assertFalse(TargetProcessPublisher.flushNow(new FakeChannel("pkg", "{}") {
            @Override
            public String buildJson() {
                throw new IllegalStateException("boom");
            }
        }));

        // resolver 为 null 时 send 抛 NPE，flushNow 必须吞掉并返回 false。
        TargetProcessPublisher.sTargetContext = new FakeContext(null);
        assertFalse(TargetProcessPublisher.flushNow(new FakeChannel("pkg", "{}")));

        // 发送层抛异常（binder 死亡/provider 崩溃）同样吞掉并返回 false。
        TargetProcessPublisher.sTransport =
                new RecordingTransport(new IllegalStateException("binder dead"));
        assertFalse(TargetProcessPublisher.flushNow(new FakeChannel("pkg", "{}")));
    }

    @Test
    public void hookStateFlushNowUsesMountsChannelAndSyncFlag() {
        HookState.init(MODULE, "main");
        RecordingTransport transport = new RecordingTransport(true);
        TargetProcessPublisher.sTransport = transport;

        assertTrue(HookState.flushNow());

        assertTrue(transport.report.sync);
        assertNull("mounts 通道保持既有线上格式，不写 type 键", transport.report.type);
        assertTrue("快照必须包含 session", transport.report.json.contains("\"session\""));
        assertEquals(Boolean.TRUE,
                TargetProcessPublisher.extrasValues(transport.report)
                        .get(StatusProvider.KEY_SYNC));
    }

    /** 同步入口不得触碰共享执行器：任何 execute/schedule 都直接失败。 */
    private static final class NoSchedulingExecutor extends ScheduledThreadPoolExecutor {
        NoSchedulingExecutor() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            throw new AssertionError("flushNow must not queue to the shared executor");
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new AssertionError("flushNow must not schedule retries");
        }
    }

    private static final class FakeContext extends ContextWrapper {
        private final ContentResolver mResolver;

        FakeContext(ContentResolver resolver) {
            super(null);
            mResolver = resolver;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }
    }

    /**
     * 记录发送边界的 Transport；{@code acknowledge=true} 模拟 StatusProvider
     * sync commit 成功（返回非 null Bundle），false 模拟 commit 失败/拒绝（返回 null）。
     */
    private static final class RecordingTransport implements TargetProcessPublisher.Transport {
        private final boolean mAcknowledge;
        private final RuntimeException mFailure;
        int calls;
        String modulePackage;
        TargetProcessPublisher.Report report;

        RecordingTransport(boolean acknowledge) {
            this(acknowledge, null);
        }

        RecordingTransport(RuntimeException failure) {
            this(false, failure);
        }

        private RecordingTransport(boolean acknowledge, RuntimeException failure) {
            mAcknowledge = acknowledge;
            mFailure = failure;
        }

        @Override
        public Bundle send(Context ctx, String modulePackage,
                           TargetProcessPublisher.Report report) {
            calls++;
            this.modulePackage = modulePackage;
            this.report = report;
            if (mFailure != null) {
                throw mFailure;
            }
            return mAcknowledge ? new Bundle() : null;
        }
    }

    private static class FakeChannel implements TargetProcessPublisher.Channel {
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
            return null;
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
}
