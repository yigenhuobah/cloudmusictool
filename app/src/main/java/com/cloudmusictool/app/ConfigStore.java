package com.cloudmusictool.app;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 *   <li>写操作返回 false 表示框架未连接、未入队；remote 可用时 commit 在
 *       单线程后台队列执行，真实结果经 {@link WriteCallback} 回主线程；</li>
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

    /** 生产构造器：不碰 binder、不读磁盘，remote 由 {@link #resolveRemote()} 绑定。 */
    ConfigStore() {
        mLocal = null;
    }

    /** 仅供单测：remote 不可用路径；读走给定存储，写一律失败且不落本地。 */
    ConfigStore(SharedPreferences local) {
        mLocal = local;
    }

    /** 仅供单测：remote 可用路径，用于验证 commit 结果的判定。 */
    ConfigStore(SharedPreferences remote, SharedPreferences local) {
        mRemote = remote;
        mLocal = local;
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
     * <p>service 断开时清空缓存，让 {@link #remote()} 如实变为 false；
     * service 换绑时重新解析，避免继续使用旧 service 的 prefs 代理。
     */
    boolean resolveRemote() {
        XposedService service = ServiceBridge.get();
        if (service == null) {
            mRemote = null;
            mRemoteService = null;
            return false;
        }
        if (mRemote != null && service == mRemoteService) {
            return true;
        }
        SharedPreferences remote = ServiceBridge.remotePreferences(Config.GROUP);
        if (remote != null) {
            mRemote = remote;
            mRemoteService = service;
            return true;
        }
        return false;
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
        return enqueue(key, editor -> editor.putBoolean(key, value), callback);
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
                // remote prefs 不支持 StringSet 时回退 CSV，读取侧 PrefsSetCodec 兼容两种格式。
                editor.putString(key, PrefsSetCodec.toCsv(value));
            }
        }, callback);
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

    private interface EditorAction {
        void apply(SharedPreferences.Editor editor);
    }
}
