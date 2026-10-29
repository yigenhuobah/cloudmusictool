package com.cloudmusictool.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.Log;

import com.cloudmusictool.app.profile.ProfileRegistry;
import com.cloudmusictool.app.profile.TargetProfile;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class VersionGate {

    public static final String SUPPORTED_VERSION = "9.6.13";
    public static final long SUPPORTED_VERSION_CODE = 9006013L;
    /** 已适配版本展示用；9.6.15 复用 9.6.13 挂载点（2026-10-29 体检 145/145）。 */
    public static final String SUPPORTED_VERSION_LABEL = "9.6.08 / 9.6.13 / 9.6.15";
    public static final String LEGACY_VERSION = "9.6.08";
    public static final long LEGACY_VERSION_CODE = 9006008L;

    private static final String TAG = "CloudMusicTool";

    private VersionGate() {
    }

    /**
     * 读取目标网易云音乐 versionCode。
     *
     * 优先 PackageReadyParam.getApplicationInfo().getLongVersionCode()；该 API 在部分
     * compileSdk stub 中不可直接引用，因此用反射调用（运行时存在即生效）。
     * 失败后回退 PackageManager.getPackageInfo(...).getLongVersionCode()；再失败返回 -1，
     * 由调用方回退 versionName。
     */
    public static long resolveVersionCode(PackageReadyParam param) {
        if (param == null) {
            return -1L;
        }
        try {
            ApplicationInfo info = param.getApplicationInfo();
            if (info != null) {
                java.lang.reflect.Method m =
                        ApplicationInfo.class.getMethod("getLongVersionCode");
                Object value = m.invoke(info);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Context ctx = targetContext();
            if (ctx != null) {
                PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(param.getPackageName(), 0);
                if (pi != null) {
                    return pi.getLongVersionCode();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    /**
     * 反射 ActivityThread 取当前应用/系统 Context，失败返回 null。
     * 应用 Context 经共享缓存助手 {@link TargetProcessPublisher#targetContext()} 解析
     * （原为每次直反射，现成功一次后缓存）；不可用时回退系统 Context（原失败语义保持）。
     */
    private static Context targetContext() {
        Context app = TargetProcessPublisher.targetContext();
        if (app != null) {
            return app;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object currentAt = at.getMethod("currentActivityThread").invoke(null);
            if (currentAt != null) {
                Object systemCtx = at.getMethod("getSystemContext").invoke(currentAt);
                if (systemCtx instanceof Context) {
                    return (Context) systemCtx;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 版本门禁 + 活动 Profile 选择。
     *
     * 已适配版本激活对应 Profile；未适配/未知版本一律 fail-open：
     * active=null，回退硬编码挂载点，best-effort 安装。
     * 与 QuietBox 语义一致，不再因为版本不匹配而整体跳过 hook。
     */
    public static boolean prepareProfile(XposedModule module, PackageReadyParam param) {
        long versionCode = resolveVersionCode(param);
        if (versionCode > 0) {
            TargetProfile profile = ProfileRegistry.forVersionCode(versionCode);
            if (profile != null) {
                ProfileRegistry.setActive(profile);
                module.log(Log.INFO, TAG, "target version matched: " + versionCode
                        + " (" + profile.versionName + ")");
                module.log(Log.INFO, TAG, "profile mounts=" + profile.mounts.size()
                        + " versionCode=" + versionCode);
                return true;
            }
            ProfileRegistry.setActive(null);
            module.log(Log.WARN, TAG, "target versionCode unsupported (" + versionCode
                    + ", supported: " + SUPPORTED_VERSION_CODE
                    + "); best-effort with hardcoded mounts");
            return true;
        }

        // versionCode 不可读：保留原有 versionName 兜底逻辑。
        String versionName = resolveVersionName(param);
        if (versionName != null) {
            TargetProfile profile = ProfileRegistry.forVersionName(versionName);
            if (profile != null) {
                ProfileRegistry.setActive(profile);
                module.log(Log.INFO, TAG, "target version matched: " + versionName
                        + " (versionCode unavailable)");
                module.log(Log.INFO, TAG, "profile mounts=" + profile.mounts.size()
                        + " versionName=" + versionName);
                return true;
            }
        }
        ProfileRegistry.setActive(null);
        return checkVersion(module, versionName);
    }

    /**
     * 读取目标网易云音乐版本名。
     * 1. 优先从 ActivityThread.currentActivityThread().getApplication() 获取 Context 并查询 PackageManager
     *    （应用 Context 反射收敛到 {@link TargetProcessPublisher#targetContext()} 共享缓存助手）。
     * 2. 失败后从 ActivityThread.currentActivityThread().getSystemContext() 的 PackageManager 查询。
     */
    public static String resolveVersionName(PackageReadyParam param) {
        if (param == null) {
            return null;
        }
        // 1. 优先从 ActivityThread.currentActivityThread().getApplication() 取 PackageManager
        try {
            Context app = TargetProcessPublisher.targetContext();
            if (app != null) {
                PackageInfo pi = app.getPackageManager().getPackageInfo(param.getPackageName(), 0);
                if (pi != null && pi.versionName != null) {
                    return pi.versionName;
                }
            }
        } catch (Throwable ignored) {
        }
        // 2. 用 ActivityThread.currentActivityThread().getSystemContext() 的 PackageManager.getPackageInfo(packageName, 0)
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object currentAt = at.getMethod("currentActivityThread").invoke(null);
            if (currentAt != null) {
                Object systemCtx = at.getMethod("getSystemContext").invoke(currentAt);
                if (systemCtx instanceof Context) {
                    PackageInfo pi = ((Context) systemCtx).getPackageManager().getPackageInfo(param.getPackageName(), 0);
                    if (pi != null && pi.versionName != null) {
                        return pi.versionName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 校验目标版本。已适配版本写 INFO；未知/未适配版本写 WARN 并 fail-open。
     */
    public static boolean checkVersion(XposedModule module, String versionName) {
        if (versionName == null) {
            module.log(Log.WARN, TAG, "unable to resolve target version; 版本未知，按 best-effort 继续");
            return true;
        }
        if (isSupportedVersion(versionName)) {
            module.log(Log.INFO, TAG, "target version matched: " + versionName);
            return true;
        }
        module.log(Log.WARN, TAG, "target version unsupported: " + versionName
                + " (supported: " + SUPPORTED_VERSION + "); best-effort with hardcoded mounts");
        return true;
    }

    public static boolean isSupportedVersion(String versionName) {
        return SUPPORTED_VERSION.equals(versionName)
                || "9.6.15".equals(versionName)
                || LEGACY_VERSION.equals(versionName);
    }
}
