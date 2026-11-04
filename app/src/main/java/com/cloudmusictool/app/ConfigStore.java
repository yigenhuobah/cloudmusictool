package com.cloudmusictool.app;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import io.github.libxposed.service.XposedService;

/**
 * 模块 App 侧的开关读写。
 * 必须写框架的 remote preferences：网易云音乐进程读的是那份存储，
 * 写进模块自己的 MODE_PRIVATE 对目标进程完全不可见。
 *
 * <p>因此本类不再回退读写本地 prefs：
 * <ul>
 *   <li>构造不做任何 binder/磁盘 I/O；remote prefs 由 {@link #resolveRemote()}
 *       在后台线程解析（首次解析是同步 binder）；</li>
 *   <li>put/putString/putStringSet 返回 false 表示框架未连接、未入队；remote 可用时
 *       commit 在单线程后台队列执行，真实结果经 {@link WriteCallback} 回主线程；</li>
 *   <li>resetToDefaults 总是先入队，在 IO 线程解析 remote 后写入，结果经
 *       {@link ResetCallback} 回主线程；调用线程不做 binder；</li>
 *   <li>读操作要求调用方先完成 {@link #resolveRemote()}，并在后台线程调用，
 *       避免主线程同步 binder。</li>
 * </ul>
 */
final class ConfigStore {

    private static final String TAG = "CloudMusicTool";

    /** 配置写队列：与读取共用，保证同一 store 上的读写按提交顺序执行。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CloudMusicTool-Config");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Handler sMainHandler;

    private volatile SharedPreferences mRemote;
    /** 绑定 mRemote 的 service 实例；service 换绑/断开后需要重新解析。 */
    private volatile XposedService mRemoteService;
    /** 仅供单测：remote 不可用时读取的存储；生产构造器恒为 null。 */
    private final SharedPreferences mLocal;
    /** 恢复默认前解析 remote 的入口；生产走 {@link #resolveRemote()}，单测注入已绑定状态。 */
    private final RemoteResolver mRemoteResolver;
    /**
     * 读回校验用的全新 remote 句柄；生产每次重新走
     * {@link ServiceBridge#remotePreferences(String)}，不复用刚 commit 的代理。
     */
    private final Supplier<SharedPreferences> mFreshRemoteOpener;

    /** 生产构造器：不碰 binder、不读磁盘，remote 由 {@link #resolveRemote()} 绑定。 */
    ConfigStore() {
        this(null, null, null, null);
    }

    /** 仅供单测：remote 不可用路径；读走给定存储，写一律失败且不落本地。 */
    ConfigStore(SharedPreferences local) {
        this(null, local, null, null);
    }

    /**
     * 仅供单测：remote 可用路径，用于验证 commit 结果的判定。
     * 读回校验默认复用同一实例；reset 用例应使用带 freshOpener 的构造器。
     */
    ConfigStore(SharedPreferences remote, SharedPreferences local) {
        this(remote, local, () -> true, () -> remote);
    }

    /** 仅供单测：remote 已注入，读回校验使用 freshOpener 提供的独立句柄。 */
    ConfigStore(SharedPreferences remote, SharedPreferences local,
                Supplier<SharedPreferences> freshOpener) {
        this(remote, local, () -> true, freshOpener);
    }

    /** 包内可见：供单测注入解析器与读回句柄。 */
    ConfigStore(SharedPreferences remote, SharedPreferences local,
                RemoteResolver resolver, Supplier<SharedPreferences> freshOpener) {
        mRemote = remote;
        mLocal = local;
        mRemoteResolver = resolver != null ? resolver : this::resolveRemote;
        mFreshRemoteOpener = freshOpener != null ? freshOpener
                : () -> ServiceBridge.remotePreferences(Config.GROUP);
    }

    /** 恢复默认前确保 remote 已解析；首次解析是同步 binder，只能在 IO 线程调用。 */
    interface RemoteResolver {
        boolean ensure();
    }

    /** 写结果判定：只有 {@link #SAVED} 表示目标进程能读到本次写入。 */
    enum WriteResult {
        /** remote 可用且 commit() 返回 true。 */
        SAVED,
        /** remote 可用但 commit() 返回 false（远端拒绝/未落盘）。 */
        REJECTED,
        /** 框架未连接或 remote prefs 不可用：没有提交任何写入。 */
        UNAVAILABLE,
        /** 写入过程抛异常。 */
        FAILED;

        boolean saved() {
            return this == SAVED;
        }

        /** 纯决策：remote 不可用时永远是 UNAVAILABLE，即使 commit 被误传 true。 */
        static WriteResult of(boolean remoteAvailable, boolean commitOk) {
            if (!remoteAvailable) {
                return UNAVAILABLE;
            }
            return commitOk ? SAVED : REJECTED;
        }

        /**
         * 写失败时 UI 必须保留旧值；只有 SAVED 才采用新值。
         * 供 UI 回调做回滚的纯函数，便于 JVM 单测。
         */
        static <T> T valueAfterWrite(WriteResult result, T before, T attempted) {
            return result != null && result.saved() ? attempted : before;
        }
    }

    /** 写结果回调，保证在主线程执行；saved 等价于 {@link WriteResult#saved()}。 */
    interface WriteCallback {
        void onResult(boolean saved);
    }

    /** 恢复默认结果回调，保证在主线程执行；success 为 false 表示未完整落盘。 */
    interface ResetCallback {
        void onResult(boolean success);
    }

    /** 配置写队列；调用方只能在后台任务里读 remote prefs。 */
    static ExecutorService io() {
        return IO;
    }

    /** 把任务投递回主线程；已在主线程（或单测无 Looper）时直接执行。 */
    static void postToMain(Runnable task) {
        if (task == null) {
            return;
        }
        Looper main = Looper.getMainLooper();
        if (main == null || Looper.myLooper() == main) {
            task.run();
            return;
        }
        Handler handler = mainHandler(main);
        if (handler == null) {
            task.run();
            return;
        }
        handler.post(task);
    }

    /**
     * 解析 remote prefs。首次调用是同步 binder，只允许在后台线程执行；
     * 已解析且 service 未换绑时直接返回，不产生 binder 调用。
     *
     * <p>service 断开或重新解析失败时清空缓存，让 {@link #remote()} 如实变为 false；
     * service 换绑时重新解析，避免继续使用旧 service 的 prefs 代理。
     */
    boolean resolveRemote() {
        XposedService service = ServiceBridge.get();
        if (service == null) {
            return applyRemote(null, null);
        }
        if (mRemote != null && service == mRemoteService) {
            return true;
        }
        return applyRemote(service, ServiceBridge.remotePreferences(Config.GROUP));
    }

    /**
     * 合并一次 remote 解析结果。remote 为 null 表示本次解析失败，必须同时清空
     * 旧代理与旧 service：否则 {@link #remote()} 仍返回 true、读取继续走已失效的
     * service 代理，与 {@link #resolveRemote()} 的返回值不一致。
     *
     * @return remote 是否可用
     */
    boolean applyRemote(XposedService service, SharedPreferences remote) {
        if (remote == null) {
            mRemote = null;
            mRemoteService = null;
            return false;
        }
        mRemote = remote;
        mRemoteService = service;
        return true;
    }

    boolean remote() {
        return mRemote != null;
    }

    boolean get(String key, boolean def) {
        SharedPreferences sp = readPrefs();
        if (sp == null) {
            return def;
        }
        try {
            return sp.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    String getString(String key, String def) {
        SharedPreferences sp = readPrefs();
        if (sp == null) {
            return def;
        }
        try {
            return sp.getString(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    Set<String> getStringSet(String key, Set<String> def) {
        SharedPreferences sp = readPrefs();
        if (sp == null) {
            return def != null ? def : Collections.emptySet();
        }
        Set<String> parsed = PrefsSetCodec.read(sp, key);
        return parsed != null ? parsed : (def != null ? def : Collections.emptySet());
    }

    /**
     * @return false 表示框架未连接：未入队，也没有写本地；true 表示已入队，
     *         真实 commit 结果经 callback 回主线程。
     */
    boolean put(String key, boolean value) {
        return put(key, value, null);
    }

    /** @see #put(String, boolean) */
    boolean put(String key, boolean value, WriteCallback callback) {
        return enqueue(key, editor -> {
            editor.putBoolean(key, value);
            // 开关写入与配置版本同事务提交：目标进程可据此识别配置格式。
            editor.putInt(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
        }, callback);
    }

    /**
     * @return false 表示框架未连接：未入队，也没有写本地；true 表示已入队，
     *         真实 commit 结果经 callback 回主线程。
     */
    boolean putString(String key, String value) {
        return putString(key, value, null);
    }

    /** @see #putString(String, String) */
    boolean putString(String key, String value, WriteCallback callback) {
        return enqueue(key, editor -> {
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putString(key, value);
            }
        }, callback);
    }

    /**
     * @return false 表示框架未连接：未入队，也没有写本地；true 表示已入队，
     *         真实 commit 结果经 callback 回主线程。
     */
    boolean putStringSet(String key, Set<String> value) {
        return putStringSet(key, value, null);
    }

    /** @see #putStringSet(String, Set) */
    boolean putStringSet(String key, Set<String> value, WriteCallback callback) {
        return enqueue(key, editor -> {
            if (value == null) {
                editor.remove(key);
                return;
            }
            try {
                editor.putStringSet(key, value);
            } catch (Throwable t) {
                // remote prefs 不支持 StringSet 时回退字符串编码，读取侧 PrefsSetCodec 兼容新旧格式。
                editor.putString(key, PrefsSetCodec.toCsv(value));
            }
        }, callback);
    }

    /**
     * 异步恢复默认配置：在单线程 io executor 上先解析/绑定 remote，再构造一次
     * editor 事务并 commit，随后用框架重新返回的句柄读回校验；结果回主线程。
     *
     * <p>恢复后的状态与全新安装一致：所有 Feature 写入编译期默认值，set 型 key
     * 与 aux key 直接移除（缺失即默认），config_version 写回当前版本。
     * 不使用 {@link SharedPreferences.Editor#clear()}，避免与 libxposed
     * remote prefs 实现不兼容；也不触碰模块本地 prefs 或背景图文件。
     *
     * @return false 表示任务未入队（executor 异常）；true 表示已入队。remote 是否
     *         可用、commit 与读回是否成功都由 callback 报告，调用线程不做 binder。
     */
    boolean resetToDefaults(ResetCallback callback) {
        try {
            IO.execute(() -> notifyResetResult(callback, resetToDefaultsBlocking()));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "config reset enqueue failed", t);
            notifyResetResult(callback, false);
            return false;
        }
    }

    /** 恢复默认要写入的键值计划；包内可见，JVM 单测直接校验。 */
    static ResetPlan buildResetPlan() {
        ResetPlan plan = new ResetPlan();
        for (Feature f : Feature.values()) {
            plan.booleans.put(f.key, f.defaultEnabled);
        }
        // 全新安装时这些 key 不存在；Config.load 对缺失 set 回退空集。
        plan.removals.add(Config.KEY_HOME_BLOCKS_FILTER);
        plan.removals.add(Config.KEY_TOP_CHANNELS_KEEP);
        plan.removals.add(Config.KEY_BOTTOM_NAV_KEEP);
        plan.removals.add(Config.KEY_STARTUP_TASK_BLOCKLIST);
        plan.removals.add(Config.KEY_ABTEST_FORCE_MAP);
        plan.removals.add(Config.KEY_DEXKIT_SELFTEST);
        plan.configVersion = Config.CURRENT_CONFIG_VERSION;
        return plan;
    }

    /** 把计划写入同一个 editor；调用方负责唯一的 commit。 */
    static void applyResetPlan(SharedPreferences.Editor editor, ResetPlan plan) {
        for (Map.Entry<String, Boolean> e : plan.booleans.entrySet()) {
            editor.putBoolean(e.getKey(), e.getValue());
        }
        for (String key : plan.removals) {
            editor.remove(key);
        }
        editor.putInt(Config.KEY_CONFIG_VERSION, plan.configVersion);
    }

    /**
     * 读回校验：所有开关必须等于计划值，所有计划移除的 key 必须不存在，
     * config_version 必须等于计划版本。布尔默认值取反，使“缺失 key”也判为
     * 不一致，避免把未落盘当成成功。
     */
    static boolean matchesResetPlan(SharedPreferences sp, ResetPlan plan) {
        for (Map.Entry<String, Boolean> e : plan.booleans.entrySet()) {
            boolean expected = e.getValue();
            if (sp.getBoolean(e.getKey(), !expected) != expected) {
                return false;
            }
        }
        for (String key : plan.removals) {
            if (sp.contains(key)) {
                return false;
            }
        }
        return sp.getInt(Config.KEY_CONFIG_VERSION, Integer.MIN_VALUE) == plan.configVersion;
    }

    private boolean resetToDefaultsBlocking() {
        try {
            // 首次解析是同步 binder：必须在 IO 线程完成，主线程只负责入队。
            if (!mRemoteResolver.ensure()) {
                Log.w(TAG, "config reset skipped (framework not bound)");
                return false;
            }
            SharedPreferences remote = mRemote;
            if (remote == null) {
                Log.w(TAG, "config reset skipped (framework not bound)");
                return false;
            }
            ResetPlan plan = buildResetPlan();
            SharedPreferences.Editor editor = remote.edit();
            applyResetPlan(editor, plan);
            if (!editor.commit()) {
                Log.w(TAG, "remote config reset rejected");
                return false;
            }
            // commit 已就地更新 remote 代理的内存状态，不能拿它自证成功：
            // 重新向框架要一份句柄再读回。若框架内部返回缓存的同一实例，
            // 这里仍重新走一次 ServiceBridge.remotePreferences，与目标进程
            // 下次读取的路径一致。
            SharedPreferences fresh = mFreshRemoteOpener.get();
            if (fresh == null) {
                Log.w(TAG, "remote config reset read-back unavailable");
                return false;
            }
            if (!matchesResetPlan(fresh, plan)) {
                Log.w(TAG, "remote config reset read-back mismatch");
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "remote config reset failed", t);
            return false;
        }
    }

    /**
     * 同步写入（commit 是 binder/磁盘 I/O），只允许后台线程调用。
     * 供需要在同一个后台任务里完成 read-modify-write 的调用方使用。
     *
     * @return 真实 commit 结果；主线程调用时拒绝写入并返回 {@link WriteResult#FAILED}。
     */
    WriteResult putStringBlocking(String key, String value) {
        if (isMainThread()) {
            Log.w(TAG, "refusing blocking remote write on main thread: " + key);
            return WriteResult.FAILED;
        }
        return commit(key, editor -> {
            if (value == null) {
                editor.remove(key);
            } else {
                editor.putString(key, value);
            }
        });
    }

    /**
     * @return false 表示框架未连接：未入队，也没有写本地；true 表示已入队，
     *         真实 commit 结果经 callback 回主线程。
     */
    private boolean enqueue(String key, EditorAction action, WriteCallback callback) {
        if (!remote()) {
            Log.w(TAG, "config write skipped (framework not bound): " + key);
            notifyResult(callback, WriteResult.UNAVAILABLE);
            return false;
        }
        try {
            IO.execute(() -> notifyResult(callback, commit(key, action)));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "config write enqueue failed: " + key, t);
            notifyResult(callback, WriteResult.FAILED);
            return false;
        }
    }

    private WriteResult commit(String key, EditorAction action) {
        SharedPreferences remote = mRemote;
        if (remote == null) {
            Log.w(TAG, "config write skipped (framework not bound): " + key);
            return WriteResult.UNAVAILABLE;
        }
        try {
            SharedPreferences.Editor editor = remote.edit();
            action.apply(editor);
            boolean ok = editor.commit();
            if (!ok) {
                Log.w(TAG, "remote config write rejected: " + key);
            }
            return WriteResult.of(true, ok);
        } catch (Throwable t) {
            Log.w(TAG, "remote config write failed: " + key, t);
            return WriteResult.FAILED;
        }
    }

    private SharedPreferences readPrefs() {
        return mRemote != null ? mRemote : mLocal;
    }

    private static boolean isMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && Looper.myLooper() == main;
    }

    private static Handler mainHandler(Looper main) {
        Handler handler = sMainHandler;
        if (handler == null) {
            try {
                handler = new Handler(main);
                sMainHandler = handler;
            } catch (Throwable t) {
                return null;
            }
        }
        return handler;
    }

    private static void notifyResult(WriteCallback callback, WriteResult result) {
        if (callback == null) {
            return;
        }
        postToMain(() -> {
            try {
                callback.onResult(result.saved());
            } catch (Throwable t) {
                Log.w(TAG, "config write callback failed", t);
            }
        });
    }

    private static void notifyResetResult(ResetCallback callback, boolean success) {
        if (callback == null) {
            return;
        }
        postToMain(() -> {
            try {
                callback.onResult(success);
            } catch (Throwable t) {
                Log.w(TAG, "config reset callback failed", t);
            }
        });
    }

    /** 恢复默认的一次性写入计划：显式 put/remove，不使用 clear()。 */
    static final class ResetPlan {
        final Map<String, Boolean> booleans = new LinkedHashMap<>();
        final Set<String> removals = new LinkedHashSet<>();
        int configVersion = Config.CURRENT_CONFIG_VERSION;
    }

    private interface EditorAction {
        void apply(SharedPreferences.Editor editor);
    }
}
