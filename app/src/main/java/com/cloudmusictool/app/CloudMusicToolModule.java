package com.cloudmusictool.app;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.cloudmusictool.app.dexkit.DexKitLocator;
import com.cloudmusictool.app.hooks.ExperimentState;
import com.cloudmusictool.app.hooks.Hooks;
import com.cloudmusictool.app.hooks.ProcessFilter;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 唯一的 Java 入口类。API 102 热重载规范要求必须恰好声明一个入口。
 * 绝不能在 onModuleLoaded 之前调用框架 API。
 * 严格禁止引用任何 androidx 类型。
 */
public class CloudMusicToolModule extends XposedModule {

    static final String TAG = "CloudMusicTool";

    private String mProcessName;

    /** UEH 幂等位：只包一次（重载/重复 onPackageReady 不重复包装）。 */
    private static boolean sCrashGuardInstalled;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        mProcessName = param.getProcessName();
        try {
            ApplicationInfo appInfo = getModuleApplicationInfo();
            if (appInfo != null) {
                if (appInfo.nativeLibraryDir != null) {
                    DexKitLocator.setModuleNativeDir(appInfo.nativeLibraryDir);
                }
                if (appInfo.sourceDir != null) {
                    DexKitLocator.setModuleApkPath(appInfo.sourceDir);
                }
                if (appInfo.packageName != null) {
                    HookState.init(appInfo.packageName, mProcessName);
                    // :play 只做音频贴片拦截，不参与 ABTest 观测，避免空快照覆盖主进程数据。
                    if (!ProcessFilter.isPlayProcess(mProcessName)) {
                        ExperimentState.init(appInfo.packageName);
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "module app info unavailable", t);
        }
        log(Log.INFO, TAG, "loaded process=" + mProcessName
                + " api=" + getApiVersion()
                + " frameworkProps=0x" + Long.toHexString(getFrameworkProperties()));
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Config.TARGET_PACKAGE.equals(param.getPackageName())) {
            detach();
            return;
        }

        boolean playProcess = ProcessFilter.isPlayProcess(mProcessName);
        if (!playProcess && !ProcessFilter.shouldHook(mProcessName)) {
            log(Log.INFO, TAG, "skip secondary process: " + mProcessName);
            detach();
            return;
        }

        log(Log.INFO, TAG, "package ready: " + param.getPackageName()
                + " process=" + mProcessName
                + " first=" + param.isFirstPackage());

        // 内置诊断：进程安装阶段包 UEH（早于任何 hook 安装，Activity 时机过晚捕不到
        // 早期崩溃），把宿主内 hook 相关闪退（GitHub issue #5）记入 crashes 通道。
        installCrashGuard();

        try {
            Config config = Config.load(this);
            // 版本门禁 + 选择活动 Profile（已适配 -> 对应 Profile；未适配 -> fail-open 回退硬编码）。
            if (!VersionGate.prepareProfile(this, param)) {
                detach();
                return;
            }
            if (Config.dexKitSelfTest()) {
                // 隐藏自检：先异步预热，避免首次 DexKit 查询落在主线程。
                DexKitLocator.warmUpAsync(param.getClassLoader(),
                        "com.netease.cloudmusic.module.ad.LoadingAdManager",
                        com.cloudmusictool.app.dexkit.DexKitAnchors.LOADING_AD_MANAGER);
            }
            if (playProcess) {
                Hooks.installPlayOnly(this, param.getClassLoader(), config);
            } else {
                Hooks.installAll(this, param.getClassLoader(), config);
                // 安装结束后的最终快照；运行期由 ExperimentState 的 2 秒节流继续回写。
                ExperimentState.flush();
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "install aborted", t);
        }
    }

    /**
     * 包装 Thread.setDefaultUncaughtExceptionHandler（内置诊断 crashes 通道的生产端）。
     *
     * 安全约束：
     * - 先保存 previous，自定义 handler 先记录崩溃（时间、线程名、异常类名+栈前 2 帧，
     *   单条 ≤ HookState.MAX_CRASH_CHARS）再调用 previous（非空时）；
     * - handler 内全程 try/catch：绝不因记录抛出、绝不改变目标 App 原有异常处理行为；
     * - 静态幂等位保证只包一次。
     */
    private void installCrashGuard() {
        if (sCrashGuardInstalled) {
            return;
        }
        try {
            Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new CrashRecordingHandler(previous));
            sCrashGuardInstalled = true;
        } catch (Throwable t) {
            // 包装失败只放弃诊断，绝不影响安装流程。
            log(Log.WARN, TAG, "crash guard unavailable", t);
        }
    }

    /** 先记录崩溃再转交 previous；自身任何一步都不抛出。 */
    private static final class CrashRecordingHandler
            implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler mPrevious;

        CrashRecordingHandler(Thread.UncaughtExceptionHandler previous) {
            mPrevious = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            try {
                HookState.recordCrash(System.currentTimeMillis(),
                        thread == null ? null : thread.getName(),
                        HookState.crashSummary(throwable));
            } catch (Throwable ignored) {
                // 记录失败不能影响原异常处理流程。
            }
            Thread.UncaughtExceptionHandler previous = mPrevious;
            if (previous != null) {
                try {
                    previous.uncaughtException(thread, throwable);
                } catch (Throwable ignored) {
                    // 全程 try/catch：previous 自己抛错也不在此二次引爆。
                }
            }
        }
    }
}
