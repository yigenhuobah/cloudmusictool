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
}
