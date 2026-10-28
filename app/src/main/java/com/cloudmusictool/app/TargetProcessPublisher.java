package com.cloudmusictool.app;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 目标进程 -> 模块 App 的共享回传管线（design-3 / P2-10 家族根治）。
 *
 * HookState（mounts）与 ExperimentState（experiments）两条回传通道原先各自维护
 * 单线程执行器、targetContext 反射、authority 字面量与 warning-once 标志，节流语义
 * 还互不一致；本类把管线共性收敛为一份：
 *
 * - 共享单线程 ScheduledExecutorService（两通道任务在同一 daemon 工作线程串行执行；
 *   线程名沿用 HookState 通道原线程名 CloudMusicTool-HookState）；
 * - 「领先 + 尾沿」节流（{@link PublishThrottle}，自 HookState 整类迁入；每通道各持
 *   一个实例，窗口互不干扰），ExperimentState 由此从纯尾沿统一为领先 + 尾沿；
 * - targetContext 反射缓存（成功一次后缓存，原 HookState/ExperimentState/
 *   DexKitLocator/VersionGate 四份 ActivityThread 反射副本收敛于此）；
 * - authority 解析复用 StatusProvider.authority(pkg)，删除各通道的字面量拼接；
 * - warning-once 的上下文不可用/发布失败日志（按通道各记一次，等价于原先各自的
 *   volatile 布尔）；
 * - context 未就绪时按 1s/2s/4s 有界重试（每通道一条链），避免冷启动首报丢失。
 *
 * 发送内容经 {@link Channel} 参数化（模块包名、type 键、JSON 构建、告警文案），
 * 节流器由各通道持有并在调用时传入。类与入口是 public 仅因 hooks/dexkit 子包的
 * 委托方（ExperimentState/DexKitLocator）需要访问，属模块内部共享实现，不对外承诺。
 */
public final class TargetProcessPublisher {

    private static final String TAG = "CloudMusicTool";
    /** 节流窗口宽度；两条通道沿用原先各自的 2 秒。 */
    private static final long PUBLISH_INTERVAL_MS = 2000L;

    /** context 未就绪时的重试间隔；耗尽后停止，等下一次状态变化再试。 */
    private static final long[] RETRY_DELAYS_MS = {1000L, 2000L, 4000L};

    /** 回传通道描述：发送内容与告警文案参数化，mounts/experiments 各一个单例实现。 */
    public interface Channel {
        /** 模块 App 包名（拼 StatusProvider authority）；null/空表示回传未初始化，跳过发送。 */
        String modulePackage();

        /** StatusProvider 的 type 键值；null 表示不写该键（mounts 通道保持既有线上格式）。 */
        String type();

        /** 生成本次快照 JSON；返回 null 表示本次不发送（无数据或构建失败）。 */
        String buildJson();

        /** 目标 Context 不可用时的告警文案（共享管线 warning-once 记录）。 */
        String contextUnavailableWarning();

        /** 发布失败时的告警文案（共享管线 warning-once 记录）。 */
        String publishFailureWarning(Throwable t);
    }

    // 包私有可整体替换：单测注入可数执行器（不启动真线程）。
    static volatile ScheduledExecutorService sExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CloudMusicTool-HookState");
        t.setDaemon(true);
        return t;
    });

    // warning-once 标志：按通道实例（单例，恒等比较）各记一次，等价于原先各自的 volatile 布尔。
    private static final Set<Channel> sContextWarned = ConcurrentHashMap.newKeySet();
    private static final Set<Channel> sFailureWarned = ConcurrentHashMap.newKeySet();
    // 重试链在途任务：同一通道同时只保留一条链，避免安装期多次 publish 重复排任务。
    // 保存 future 才能在成功/短路/耗尽时取消已排任务——只清标记的话，已排任务仍会执行
    // 并重复发送。所有访问都在 sRetryLock 内（schedule 与 cancel 需要成对原子）。
    private static final Map<Channel, ScheduledFuture<?>> sRetryPending = new HashMap<>();
    private static final Object sRetryLock = new Object();

    // 包私有可整体替换：单测置空/注入（与 sExecutor 同）；生产只由 targetContext() 写入。
    static volatile Context sTargetContext;

    private TargetProcessPublisher() {
    }

    /**
     * 节流提交一条通道的状态变化：窗口首个变化立即发送（领先沿），窗口内后续变化
     * 在窗口关闭时合并为一次尾沿（携带最新状态）。节流器由通道持有（每通道一个）。
     */
    public static void publish(PublishThrottle throttle, Channel channel) {
        PublishThrottle.Decision d = throttle.decide();
        if (d.sendNow) {
            sExecutor.execute(() -> doPublish(channel, 0));
        }
        if (d.hasTrailing()) {
            sExecutor.schedule(() -> {
                if (throttle.onWindowClose()) {
                    doPublish(channel, 0);
                }
            }, d.trailingDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 绕过节流立即提交一次最终快照（HookState/ExperimentState 的 flush 入口）。 */
    public static void flush(Channel channel) {
        sExecutor.execute(() -> doPublish(channel, 0));
    }

    private static void doPublish(Channel channel, int attempt) {
        String pkg = channel.modulePackage();
        if (pkg == null || pkg.isEmpty()) {
            cancelRetry(channel);
            return;
        }
        String json = channel.buildJson();
        if (json == null) {
            cancelRetry(channel);
            return;
        }
        Context ctx = targetContext();
        if (ctx == null) {
            if (sContextWarned.add(channel)) {
                Log.w(TAG, channel.contextUnavailableWarning());
            }
            scheduleContextRetry(channel, attempt);
            return;
        }
        try {
            Bundle extras = new Bundle();
            String type = channel.type();
            if (type != null) {
                extras.putString(StatusProvider.KEY_TYPE, type);
            }
            extras.putString(StatusProvider.KEY_JSON, json);
            ctx.getContentResolver().call(StatusProvider.authority(pkg), "report", null, extras);
        } catch (Throwable t) {
            if (sFailureWarned.add(channel)) {
                Log.w(TAG, channel.publishFailureWarning(t));
            }
        } finally {
            // 发送成功（或失败）即视为本次发布完成：取消仍在排队的重试，避免迟到任务
            // 在状态已成功回传后再次发送。
            cancelRetry(channel);
        }
    }

    /**
     * context 未就绪时排下一次重试；同一通道同时只保留一条链。任务执行前先移除
     * 在途 future，使下一次仍失败时能继续排后续重试；链耗尽时同样取消并清空。
     */
    private static void scheduleContextRetry(Channel channel, int attempt) {
        synchronized (sRetryLock) {
            if (attempt >= RETRY_DELAYS_MS.length) {
                cancelRetryLocked(channel);
                return;
            }
            if (sRetryPending.containsKey(channel)) {
                return;
            }
            ScheduledFuture<?> future;
            try {
                future = sExecutor.schedule(() -> {
                    synchronized (sRetryLock) {
                        sRetryPending.remove(channel);
                    }
                    doPublish(channel, attempt + 1);
                }, RETRY_DELAYS_MS[attempt], TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                return;
            }
            // future 为 null 只可能来自不守约的执行器实现（真实 ExecutorService 不会）；
            // 此时仍保留占位以维持单链语义，只是无法取消。
            sRetryPending.put(channel, future);
        }
    }

    /** 取消并移除该通道的在途重试（成功/短路/耗尽路径共用）。 */
    private static void cancelRetry(Channel channel) {
        synchronized (sRetryLock) {
            cancelRetryLocked(channel);
        }
    }

    private static void cancelRetryLocked(Channel channel) {
        ScheduledFuture<?> future = sRetryPending.remove(channel);
        if (future != null) {
            // false：不打断正在执行的任务（其自身会移除 future），只阻止未启动的重试。
            future.cancel(false);
        }
    }

    /** 仅供单测重置重试链状态（生产不调用）。 */
    static void resetRetryStateForTest() {
        synchronized (sRetryLock) {
            for (ScheduledFuture<?> future : sRetryPending.values()) {
                if (future != null) {
                    future.cancel(false);
                }
            }
            sRetryPending.clear();
        }
    }

    /** 反射 ActivityThread.currentActivityThread().getApplication()，成功一次后缓存；失败返回 null。 */
    public static Context targetContext() {
        Context cached = sTargetContext;
        if (cached != null) {
            return cached;
        }
        synchronized (TargetProcessPublisher.class) {
            if (sTargetContext != null) {
                return sTargetContext;
            }
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread == null) {
                    return null;
                }
                Object app = at.getMethod("getApplication").invoke(thread);
                if (app instanceof Context) {
                    sTargetContext = (Context) app;
                }
            } catch (Throwable t) {
                return null;
            }
            return sTargetContext;
        }
    }

    /**
     * 领先 + 尾沿节流决策（评审 concurrency-4：旧实现只做领先沿，2 秒窗口内的变化
     * 被直接丢弃且窗口推进先于队列 CAS，最后一次状态变化可能永不上报）。
     *
     * - 窗口首个变化：开窗并立即发送（领先沿，保持安装期响应性）；
     * - 窗口内后续变化：只置 pending，尾沿检查任务只排队一次；
     * - 窗口关闭：pending 置位则发送一次尾沿（发送时才取快照，携带最新状态），
     *   随后关窗，下一次变化重新开窗走领先沿。
     *
     * 时钟经 {@link Clock} 注入、决策与发送解耦，纯 JVM 直测（HookStatePublishScheduleTest）。
     */
    public static final class PublishThrottle {

        /** 时钟抽象：生产用系统时钟，测试注入假时钟。 */
        public interface Clock {
            long now();
        }

        /**
         * 生产时钟：单调毫秒。节流只关心时间差，墙钟（currentTimeMillis）被回拨时
         * now - start 变负、窗口长期不关闭（事件被吞）；向前跳变又会提前关窗造成
         * 重复发送。nanoTime 只保证差值单调，正是窗口判定需要的语义。
         */
        public static final Clock MONOTONIC_CLOCK = () -> System.nanoTime() / 1_000_000L;

        /** 一次变化的调度决策：sendNow 立即发送；hasTrailing() 时需在 trailingDelayMs 后检查尾沿。 */
        public static final class Decision {
            public final boolean sendNow;
            public final long trailingDelayMs;

            private Decision(boolean sendNow, long trailingDelayMs) {
                this.sendNow = sendNow;
                this.trailingDelayMs = trailingDelayMs;
            }

            /** 是否需要排队一次尾沿检查任务。 */
            public boolean hasTrailing() {
                return trailingDelayMs != NO_TRAILING;
            }
        }

        /** 窗口关闭（无窗口）标记；取 -1 避免与「窗口开在时刻 0」的合法起点冲突。 */
        private static final long NO_WINDOW = -1L;
        /** 无需调度尾沿的标记。 */
        private static final long NO_TRAILING = -1L;

        private final Clock mClock;
        /**
         * 以下三个字段只由 decide()/onWindowClose() 读写，两方法均为 synchronized：
         * 关窗必须原子地「置关窗 + 消费 pending」，否则并发 decide 在关窗后置的
         * pending 会被旧窗口的关窗误消费（或被新窗口的关窗提前消费）。
         */
        /** 当前窗口起点；NO_WINDOW 表示窗口关闭。 */
        private long mWindowStart = NO_WINDOW;
        /** 窗口内有变化待尾沿发送。 */
        private boolean mPending;
        /** 尾沿检查任务是否已排队。 */
        private boolean mTrailingQueued;

        public PublishThrottle(Clock clock) {
            mClock = clock;
        }

        /**
         * 计算一次状态变化的调度决策。与 {@link #onWindowClose()} 互斥（synchronized），
         * 保证关窗只消费属于当前窗口的 pending。
         */
        public synchronized Decision decide() {
            long now = mClock.now();
            long start = mWindowStart;
            if (start == NO_WINDOW || now - start >= PUBLISH_INTERVAL_MS) {
                // 无窗口或窗口已过期：本变化开新窗，立即发送（领先沿）。
                mWindowStart = now;
                return new Decision(true, NO_TRAILING);
            }
            // 窗口内：只置 pending，尾沿检查任务排队一次，不重复排。
            mPending = true;
            if (!mTrailingQueued) {
                mTrailingQueued = true;
                return new Decision(false, PUBLISH_INTERVAL_MS - (now - start));
            }
            return new Decision(false, NO_TRAILING);
        }

        /**
         * 窗口关闭时检查尾沿：pending 置位则消费并返回 true（调用方发送一次尾沿），
         * 无论是否发送都关窗，允许下一次变化重新开窗走领先沿。
         */
        public synchronized boolean onWindowClose() {
            mTrailingQueued = false;
            mWindowStart = NO_WINDOW;
            boolean pending = mPending;
            mPending = false;
            return pending;
        }
    }
}
