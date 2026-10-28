package com.cloudmusictool.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

import org.json.JSONObject;

/**
 * 目标进程 -> 模块 App 的 Hook 状态回写通道。
 *
 * 为什么需要它：libxposed 的 remote preferences 在宿主进程是只读的，
 * 目标进程无法把运行时状态写回去；这里用一个 exported ContentProvider 接收
 * 目标进程的 call("report")，并在 provider 内校验调用方 UID 必须属于
 * com.netease.cloudmusic，其他 UID 一律拒绝。
 */
public class StatusProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".status";
    public static final String KEY_JSON = "json";
    public static final String KEY_OK = "ok";
    /** 回写类型：缺省 mounts（Hook 健康度），experiments 为 ABTest 观测。 */
    public static final String KEY_TYPE = "type";
    public static final String TYPE_MOUNTS = "mounts";
    public static final String TYPE_EXPERIMENTS = "experiments";
    /** 「我的」页背景图片的固定 URI 路径；只接受这一个路径，避免目录穿越。 */
    public static final String PATH_MINE_BACKGROUND = "/mine_background";
    /** 背景文件元数据列：目标进程据此判断缓存是否过期。 */
    public static final String COLUMN_LENGTH = "length";
    public static final String COLUMN_LAST_MODIFIED = "last_modified";

    private static final String MIME_JPEG = "image/jpeg";

    static final String PREFS = "hook_status";
    static final String KEY_STATE = "state";
    static final String KEY_EXPERIMENTS = "experiments";

    private static final String TAG = "CloudMusicTool";
    private static final String TARGET_PACKAGE = Config.TARGET_PACKAGE;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return handle(method, extras);
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        return handle(method, extras);
    }

    private Bundle handle(String method, Bundle extras) {
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        int uid = Binder.getCallingUid();
        if (!isTargetUid(uid)) {
            Log.w(TAG, "status report rejected: uid=" + uid);
            return null;
        }
        String json = extras.getString(KEY_JSON);
        if (!ReportProtocol.isValidPayload(json)) {
            Log.w(TAG, "status report rejected: invalid payload");
            return null;
        }
        String type = extras.getString(KEY_TYPE, TYPE_MOUNTS);
        // type 只认 mounts/experiments 两种：未知 type 一律拒绝，
        // 否则调用方换个 type 字符串即可绕过 mounts 通道的门禁、直接覆盖主进程快照。
        if (!TYPE_MOUNTS.equals(type) && !TYPE_EXPERIMENTS.equals(type)) {
            Log.w(TAG, "status report rejected: unknown type=" + type);
            return null;
        }
        boolean experiments = TYPE_EXPERIMENTS.equals(type);
        // 凡落地 KEY_STATE（主进程快照）的报告一律过主进程门禁：
        // :play 只装音频贴片，回传会覆盖主进程快照；只保留主进程数据。
        if (!experiments && !ReportProtocol.isMainProcessReport(json)) {
            Log.i(TAG, "status report ignored: secondary process or invalid payload");
            return null;
        }
        // 结构白名单：payload 必须与生产者 schema 一致（ReportProtocol 的真实契约），
        // 半信任调用方写入未知键/超长字符串/超量条目在此整体拒绝。
        try {
            JSONObject root = new JSONObject(json);
            String reason = experiments
                    ? ReportProtocol.validateExperimentsPayload(root)
                    : ReportProtocol.validateMountsPayload(root);
            if (reason != null) {
                Log.w(TAG, "status report rejected: " + reason);
                return null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "status report rejected: malformed json");
            return null;
        }
        String prefsKey = experiments ? KEY_EXPERIMENTS : KEY_STATE;
        try {
            prefs().edit().putString(prefsKey, json).apply();
        } catch (Throwable t) {
            Log.w(TAG, "status store failed", t);
            return null;
        }
        Bundle out = new Bundle();
        out.putBoolean(KEY_OK, true);
        return out;
    }

    private boolean isTargetUid(int uid) {
        try {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (String pkg : packages) {
                if (TARGET_PACKAGE.equals(pkg)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "uid check failed", t);
        }
        return false;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 模块 App 内读取最近一次状态 JSON。 */
    public static String readState(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_STATE, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clearState(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_STATE).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 已启用且明确失败的功能数；供主页面异常横幅使用。 */
    public static int failedFeatureCount(Context context) {
        String json = readState(context);
        if (json == null || json.isEmpty()) {
            return 0;
        }
        try {
            return ReportProtocol.failedFeatureCount(new org.json.JSONObject(json));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 模块 App 内读取最近一次 ABTest 观测 JSON（type=experiments）。 */
    public static String readExperiments(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_EXPERIMENTS, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 背景图片只读 URI；目标进程用它经 openFile 读取本地图片。 */
    public static Uri mineBackgroundUri(Context context) {
        return Uri.parse("content://" + context.getPackageName() + AUTHORITY_SUFFIX
                + PATH_MINE_BACKGROUND);
    }

    /**
     * Hook 状态回写通道的 authority URI：content://&lt;pkg&gt;.status。
     * 目标进程经 ContentResolver.call("report") 回写；与 Manifest 的
     * ${applicationId}.status 一致（pkg 即模块 App 包名）。
     */
    public static Uri authority(String pkg) {
        return Uri.parse("content://" + pkg + AUTHORITY_SUFFIX);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // 1) 只接受固定路径，绝不把 uri 当文件路径使用。
        if (uri == null || !PATH_MINE_BACKGROUND.equals(uri.getPath())) {
            throw new FileNotFoundException("unsupported uri: " + uri);
        }
        // 2) 打开文件前先校验调用方必须是网易云音乐。
        int uid = Binder.getCallingUid();
        if (!isTargetUid(uid)) {
            Log.w(TAG, "mine background read rejected: uid=" + uid);
            throw new FileNotFoundException("uid not allowed");
        }
        // 3) 只提供只读访问。
        if (mode != null && !mode.contains("r")) {
            throw new FileNotFoundException("read-only provider");
        }
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("provider not ready");
        }
        File file = MineBackgroundStore.file(context);
        if (!file.isFile() || file.length() <= 0) {
            throw new FileNotFoundException("mine background not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor fd = openFile(uri, mode);
        return fd == null ? null : new AssetFileDescriptor(fd, 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // 只暴露固定背景路径的元数据；调用方必须仍是网易云音乐。
        if (uri == null || !PATH_MINE_BACKGROUND.equals(uri.getPath())) {
            return null;
        }
        int uid = Binder.getCallingUid();
        if (!isTargetUid(uid)) {
            Log.w(TAG, "mine background query rejected: uid=" + uid);
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(
                new String[]{COLUMN_LENGTH, COLUMN_LAST_MODIFIED});
        Context context = getContext();
        if (context == null) {
            return cursor;
        }
        File file = MineBackgroundStore.file(context);
        // 文件不存在时返回 0 行：目标进程据此把「已清除」与「查询失败」区分开。
        if (file.isFile() && file.length() > 0) {
            cursor.addRow(new Object[]{file.length(), file.lastModified()});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        if (uri != null && PATH_MINE_BACKGROUND.equals(uri.getPath())) {
            return MIME_JPEG;
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
