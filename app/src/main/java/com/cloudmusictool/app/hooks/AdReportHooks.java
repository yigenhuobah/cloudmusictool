package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-03 广告上报与外部应用静默跳转拦截。
 */
public final class AdReportHooks {

    private AdReportHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.AD_REPORT_JUMP;

        // 1. 广告上报任务 execute() -> true（视为上报完成，但不发送网络请求）
        try {
            Class<?> reportTaskCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.module.ad.AdCloudReportTask",
                    "ADS2S");
            if (reportTaskCls != null) {
                Method execute = HookKit.findMethod(module, f, reportTaskCls, "execute");
                HookKit.install(module, f, "AdCloudReportTask#execute", execute,
                        HookKit.constBool(module, f, "AdCloudReportTask.execute -> true", true));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdCloudReportTask hook failed", t);
        }

        // 2. 外部 App 静默跳转拦截 StartAppHelper$b.startApp -> false
        try {
            Class<?> helperBCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.ad.StartAppHelper$b");
            if (helperBCls != null) {
                for (Method m : helperBCls.getDeclaredMethods()) {
                    if ("startApp".equals(m.getName())
                            && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                        HookKit.install(module, f, "StartAppHelper$b#" + m.getName() + "(" + m.getParameterTypes().length + ")",
                                m, HookKit.constBool(module, f, "StartAppHelper$b.startApp -> false", false));
                    }
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " StartAppHelper$b hook failed", t);
        }

        // 3. 广告曝光 Deeplink 预请求任务 init() -> return
        try {
            Class<?> preReqCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.process.task.app.attach.AdExposureDeeplinkPreRequestTask");
            if (preReqCls != null) {
                Method init = HookKit.findMethod(module, f, preReqCls, "init");
                HookKit.install(module, f, "AdExposureDeeplinkPreRequestTask#init", init,
                        HookKit.skipVoid(module, f, "AdExposureDeeplinkPreRequestTask.init"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdExposureDeeplinkPreRequestTask hook failed", t);
        }

        // 4. 广告曝光 Deeplink 任务 init() -> return
        try {
            Class<?> deeplinkCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.process.task.app.create.AdExposureDeeplinkTask");
            if (deeplinkCls != null) {
                Method init = HookKit.findMethod(module, f, deeplinkCls, "init");
                HookKit.install(module, f, "AdExposureDeeplinkTask#init", init,
                        HookKit.skipVoid(module, f, "AdExposureDeeplinkTask.init"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdExposureDeeplinkTask hook failed", t);
        }
    }
}
