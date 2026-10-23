package com.cloudmusictool.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * 桌面图标显隐控制。
 *
 * <p>承载 LAUNCHER 入口的是 manifest 里的 activity-alias（LauncherEntry），
 * 所以「隐藏图标」只是把该 alias 置为 disabled，MainActivity 本身仍然带
 * {@code de.robv.android.xposed.category.MODULE_SETTINGS} 入口，
 * 依然可以从 LSPosed 管理器打开设置页把图标恢复回来，不会把自己锁在外面。
 *
 * <p>状态以系统的组件状态为唯一真相源：即使用户清掉 App 数据或用 adb 改过组件状态，
 * 开关显示也和桌面实际表现一致。本地 prefs 只在读组件状态未知或抛异常时兜底。
 */
public final class LauncherIcon {

    private static final String TAG = "CloudMusicTool";

    /** 本地备份，仅在组件状态读不到时使用。 */
    private static final String PREFS = "launcher_icon";
    private static final String KEY_HIDDEN = "hidden";

    /** 与 AndroidManifest.xml 中 activity-alias 的 name 保持一致（namespace 前缀，非 applicationId）。 */
    static final String ALIAS_CLASS = "com.cloudmusictool.app.LauncherEntry";

    private LauncherIcon() {
    }

    static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(), ALIAS_CLASS);
    }

    /**
     * 组件启用状态到「图标是否隐藏」的映射。
     *
     * @return TRUE 表示已隐藏，FALSE 表示未隐藏，null 表示状态未知
     */
    static Boolean isHiddenState(int state) {
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return Boolean.TRUE;
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /** 桌面图标当前是否处于隐藏状态。 */
    public static boolean isHidden(Context context) {
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(alias(context));
            Boolean hidden = isHiddenState(state);
            if (hidden != null) {
                // 系统状态是唯一真相源：顺手刷新本地备份，避免兜底值过期。
                if (prefs(context).getBoolean(KEY_HIDDEN, false) != hidden) {
                    prefs(context).edit().putBoolean(KEY_HIDDEN, hidden).apply();
                }
                return hidden;
            }
        } catch (Throwable t) {
            Log.w(TAG, "read launcher alias state failed", t);
        }
        return prefs(context).getBoolean(KEY_HIDDEN, false);
    }

    /**
     * 切换桌面图标显隐。
     *
     * @return true 表示系统已接受新的组件状态；false 表示写入失败，调用方应回滚 UI
     */
    public static boolean setHidden(Context context, boolean hidden) {
        int state = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        boolean ok;
        try {
            // DONT_KILL_APP：只改组件状态，不能连带杀掉当前进程，否则设置页会当场闪退。
            context.getPackageManager().setComponentEnabledSetting(
                    alias(context), state, PackageManager.DONT_KILL_APP);
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "set launcher alias state failed", t);
            ok = false;
        }
        // 只有系统接受后才写本地备份，失败时保持原值，避免下次兜底读回错误状态。
        if (ok) {
            prefs(context).edit().putBoolean(KEY_HIDDEN, hidden).apply();
        }
        return ok;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
